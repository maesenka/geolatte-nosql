package nosql

import akka.actor.Props
import kamon.Kamon
import metrics.{PrometheusMetrics, SimplePrinter, KamonUserMetrics}
import org.slf4j.LoggerFactory
import play.api._
import play.api.mvc.Results._
import play.api.mvc._

import scala.concurrent.Future

object Global extends GlobalSettings {


  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  lazy val prometheusMetrics = new PrometheusMetrics

  override def onError(request: RequestHeader, ex: Throwable) = {

    val useful = ex match {
      case e: UsefulException => e
      case _ => UnexpectedException(unexpected = Some(ex))
    }
    Logger.error(s"Error on request", useful)
    Future { InternalServerError(views.html.defaultpages.error(useful)) }
  }

  override def onHandlerNotFound(request: RequestHeader): Future[Result] = {
    Future { NotFound(s"Request ${request.path} not found.") }
  }

  override def onStart(app: Application) {

      Kamon.start()
      prometheusMetrics.start()

//      val subscriber = app.actorSystem.actorOf(Props[SimplePrinter], "kamon-stdout-reporter")
//      Kamon.metrics.subscribe("request", "**", subscriber)
  }

  val requestLogger = LoggerFactory.getLogger("requests")

  val loggingFilter = Filter { (nextFilter, requestHeader) =>
    val startTime = System.currentTimeMillis
    val timer = prometheusMetrics.requestLatency.startTimer()

    nextFilter(requestHeader).map { result =>
      if (requestHeader.path.contains("metrics"))
        result
      else {
        val endTime = System.currentTimeMillis
        val requestTime = (endTime - startTime)

        val metrics = Kamon.metrics.entity( KamonUserMetrics, "featureserver-request" )
        metrics.requests.increment( )
        metrics.requestExecutionTime.record( requestTime )
        prometheusMetrics.totalRequests.inc()
        timer.observeDuration()

        if ( result.header.status != 200 ) {
          metrics.errors.increment( )
          prometheusMetrics.failedRequests.inc()
        }

        requestLogger.info( s"${requestHeader.method} ${requestHeader.uri} ; $requestTime ; ${result.header.status}" )
        result.withHeaders( "Request-Time" -> requestTime.toString )
      }
    }
  }

  override def onStop(app: Application): Unit = {
    Kamon.shutdown()
    //TODO also stop repository connection pools (e.g. postgresql-async!)
  }


}



