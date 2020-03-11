package controllers.tamagoya

import com.typesafe.config.ConfigFactory
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsSuccess, Json}
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import com.sendgrid.{Method, Request, SendGrid}
import com.sendgrid.helpers.mail.Mail
import com.sendgrid.helpers.mail.objects.{Content, Email}

import org.joda.time.{DateTime, DateTimeZone}

@Singleton
class TamagoyaApp @Inject()(cc: ControllerComponents, ws: WSClient) extends AbstractController(cc) {

  def takeOrder = Action { _ =>

    try {
      val now = DateTime.now(DateTimeZone.forID("Asia/Tokyo"))

      val publicHoliday = getPublicHoliday(now)

      val messageJson = if (now.getDayOfWeek < 1 || 5 < now.getDayOfWeek) {
        None
      } else if (publicHoliday.nonEmpty) {
        val publicHolidayJson = Json.obj(
          "blocks" -> Json.arr(
            Json.obj(
              "type" -> "section",
              "text" -> Json.obj(
                "type" -> "mrkdwn",
                "text" -> s"本日は *${publicHoliday.get}* です。"
              )
            )
          )
        )

        Some(publicHolidayJson)
      } else {
        val takeOrderJson = Json.obj(
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

        Some(takeOrderJson)
      }

      messageJson match {
        case Some(json) =>  val url = ConfigFactory.load().getString("tamagoya.takeOrder.webhookUrl")
          ws.url(url).addHttpHeaders("Content-Type" -> "application/json")
            .withRequestTimeout(5000.millis)
            .post(json)
        case None =>
      }

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

          val config = ConfigFactory.load()

          val json = Json.parse(payload)

          val responseUrl = (json \ "response_url").as[String]
          val userName = (json \ "user" \ "username").as[String]
          val userId = (json \ "user" \ "id").as[String]
          val messageTs = (json \ "container" \ "message_ts").as[String]
          val order = (json \ "actions" \ 0 \ "value").as[String]
          val orderText = (json \ "actions" \ 0 \ "text" \ "text").as[String]

          val message = try {
            val now = DateTime.now(DateTimeZone.forID("Asia/Tokyo"))
            val messageDate = new DateTime(new java.util.Date(messageTs.split('.')(0).toLong * 1000), DateTimeZone.forID("Asia/Tokyo"))
            println(now)
            println(messageDate)

            val orderStop = config.getString("tamagoya.orderStopTime").split(':')
            val orderStopMinutes = orderStop(0).toInt * 60 + orderStop(1).toInt

            if (messageDate.getDayOfMonth != now.getDayOfMonth || now.getMinuteOfDay > orderStopMinutes) { // TODO 別の日チェックはちゃんとやる
              s"<@$userId> 本日の注文は締め切られました。"
            } else {

              // メール送信
              val mailJson = Json.obj(
                "date" -> now.toString("yyyy-MM-dd HH:mm:ss"),
                "username" -> userName,
                "order" -> order
              )

              val from = new Email(config.getString("tamagoya.email.from"))
              val subject = "Tamagoya/Lunch Order"
              val to = new Email(config.getString("tamagoya.email.to"))
              val content = new Content("text/plain", mailJson.toString())
              val mail = new Mail(from, subject, to, content)

              val sg = new SendGrid(config.getString("sendgrid.apiKey"))
              val mailRequest = new Request
              mailRequest.setMethod(Method.POST)
              mailRequest.setEndpoint("mail/send")
              mailRequest.setBody(mail.build)
              sg.api(mailRequest)

              // slackへのレスポンス
              if (order == "cancel") s"$userName がキャンセルしました。" else s"$userName が *$orderText* を注文しました。"
            }
          } catch {
            case ex: Exception =>
              ex.printStackTrace()

              s"<@$userId> 予期せぬエラーが発生しました。"

          }

          // ボタンのレスポンスにすると一人しか受け付けられないので、スレッドでリプライしておく
          val res = Json.obj(
            "thread_ts" -> messageTs,
            "as_user" -> true,
            "user_id" -> userId,
            "blocks" -> Json.arr(
              Json.obj(
                "type" -> "section",
                "text" -> Json.obj(
                  "type" -> "mrkdwn",
                  "text" -> message
                )
              )
            )
          )

          ws.url(config.getString("tamagoya.takeOrder.webhookUrl")).addHttpHeaders("Content-Type" -> "application/json")
            .withRequestTimeout(5000.millis)
            .post(res)

          Ok
        case None => Ok
      }
    } catch {
      case e: Exception => e.printStackTrace()
        InternalServerError
    }
  }

  /**
   * 祝日判定
   * @param date
   * @return
   */
  def getPublicHoliday(date: DateTime): Option[String] = {
    try {
      ws.url("https://holidays-jp.github.io/api/v1/date.json")
        .withRequestTimeout(5000.millis)
        .get()
        .map {
          response =>
            (response.json \ date.toString("yyyy-MM-dd")).validate[String] match {
              case JsSuccess(value, path) => return Option(value)
              case _ =>
            }
        }
    } catch {
      case e:Exception => e.printStackTrace()
    }

    None
  }
}
