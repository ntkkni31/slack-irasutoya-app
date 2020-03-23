package controllers

import javax.inject.{Inject, Singleton}
import org.apache.commons.codec.net.URLCodec
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class DevToolsApp @Inject()(cc: ControllerComponents, ws: WSClient) extends AbstractController(cc) {

  def command = Action { request =>

    try {
      request.body.asFormUrlEncoded match {
        case Some(x) =>
          val arg = x("text").head
          val responseUrl = x("response_url").head
          val command = x("command").head
          val userId = x("user_id").head

          if(arg != null && arg.length > 0) {
            command match {
              case "/url_encode" =>
                Ok(createConvertedMessage(arg, new URLCodec("UTF-8").encode(arg)))
              case "/url_decode" =>
                Ok(createConvertedMessage(arg, new URLCodec("UTF-8").decode(arg)))

              case _ => Ok
            }
          } else {
            Ok
          }

        case None => Ok
      }

    } catch {
      case e:Exception => e.printStackTrace()
        Ok
    }

  }

  private def createConvertedMessage(source: String, destination: String) = {
    Json.obj(
      "blocks" -> Json.arr(
        Json.obj(
          "type" -> "section",
          "text" -> Json.obj(
            "type" -> "mrkdwn",
            "text" -> s"変換前: $source"
          )
        ),
        Json.obj(
          "type" -> "section",
          "text" -> Json.obj(
            "type" -> "mrkdwn",
            "text" -> s"変換後: $destination"
          )
        )
      )
    )
  }
}
