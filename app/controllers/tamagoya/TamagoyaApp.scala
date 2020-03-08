package controllers.tamagoya

import com.typesafe.config.ConfigFactory
import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class TamagoyaApp @Inject()(cc: ControllerComponents, ws: WSClient) extends AbstractController(cc) {
  def takeOrder = Action { request =>

    try {
      val takeOrderJson = Json.obj(
        "response_type" -> "in_channel",
        "blocks" -> Json.arr(
          Json.obj(
            "type" -> "section",
            "text" -> Json.obj(
              "type" -> "mrkdwn",
              "text" -> "注文を選択してください。"
            )
          ),
          Json.obj(
            "type" -> "actions",
            "elements" -> Json.arr(
              Json.obj(
                "type" -> "button",
                "text" -> Json.obj(
                  "type" -> "plain_text",
                  "text" -> "普通(470円)",
                  "emoji" -> true
                ),
                "value" -> "normal"
              ),
              Json.obj(
                "type" -> "button",
                "text" -> Json.obj(
                  "type" -> "plain_text",
                  "text" -> "小(450円)",
                  "emoji" -> true
                ),
                "value" -> "small"
              ),
              Json.obj(
                "type" -> "button",
                "text" -> Json.obj(
                  "type" -> "plain_text",
                  "text" -> "キャンセル",
                  "emoji" -> true
                ),
                "value" -> "cancel",
                "style" -> "danger"
              )
            )
          )
        )
      )

      val url = ConfigFactory.load().getString("tamagoya.takeOrder.webhookUrl")
      ws.url(url).addHttpHeaders("Content-Type" -> "application/json")
        .withRequestTimeout(5000.millis)
        .post(takeOrderJson)

    } catch {
      case e: Exception => e.printStackTrace()
    }
    Ok
  }

  def acceptOrder = Action { request =>
    try {
      request.body.asFormUrlEncoded match {
        case Some(x) =>
          val payload = x("payload").head

          println(payload)

          val json = Json.parse(payload)

          val responseUrl = (json \ "response_url").as[String]
          val userName = (json \ "user" \ "username").as[String]
          val userId = (json \ "user" \ "id").as[String]
          val order = (json \ "actions" \ 0 \ "value").as[String]

          val messageJson = Json.obj(
            "response_type" -> "in_channel",
            "as_user" -> true,
            "user_id" -> userId,
            "blocks" -> Json.arr(
              Json.obj(
                "type" -> "section",
                "text" -> Json.obj(
                  "type" -> "mrkdwn",
                  "text" -> s"${order}を注文しました"
                )
              )
            )
          )

          ws.url(responseUrl).addHttpHeaders("Content-Type" -> "application/json")
            .withRequestTimeout(5000.millis)
            .post(messageJson)

        case None => println("none")
      }
    } catch {
      case e: Exception => e.printStackTrace()
    }
    Ok
  }
}
