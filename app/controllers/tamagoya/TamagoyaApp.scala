package controllers.tamagoya

import com.typesafe.config.ConfigFactory
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import com.sendgrid.{Method, Request, SendGrid}
import com.sendgrid.helpers.mail.Mail
import com.sendgrid.helpers.mail.objects.{Content, Email}
import org.joda.time.{DateTime, DateTimeZone}

@Singleton
class TamagoyaApp @Inject()(cc: ControllerComponents, ws: WSClient) extends AbstractController(cc) {
  private val config = ConfigFactory.load()

  def actionEndpoint: Action[AnyContent] = Action { request =>
    request.body.asJson match {
      case Some(json) =>
        (json \ "challenge").validate[String] match {
          case JsSuccess(challenge, _) =>
            (json \ "type").validate[String] match {
              case JsSuccess(typeValue, _) => if (typeValue == "url_verification" && (json \ "token").validate[String].isSuccess) {
                Ok(challenge).as("text/plain")
              } else {
                NoContent
              }
              case _ => NoContent
            }
          case _ =>

            if (isTrigger(json)) {
              postTakeOrderMessage
            }

            Ok
        }
      case None => NoContent
    }
  }

  /**
   * オーダーメッセージを送信するトリガー判定
   * @param json
   * @return
   */
  private def isTrigger(json: JsValue): Boolean = {
    val channel = (json \ "event" \ "channel").validate[String] match {
      case JsSuccess(v, _) => v
      case _ => null
    }

    val botId = (json \ "event" \ "bot_id").validate[String] match {
      case JsSuccess(v, _) => v
      case _ => null
    }

    if (channel == config.getString("tamagoya.channel")) {
      println(json)
    }

    // 特定のボットが特定のメッセージを送信したら
    if (channel == config.getString("tamagoya.channel") && botId == config.getString("tamagoya.todayBot")) {
      (json \ "event" \ "text").validate[String] match {
        case JsSuccess(text, _) => text.contains("本日のたまごや")
        case _ => false
      }
    } else {
      false
    }
  }

  /**
   * トリガーでうまく動かなかったとき用に、URL叩いて動かせるように
   * @return
   */
  def takeOrder: Action[AnyContent] = Action { _ =>
    postTakeOrderMessage
    Ok
  }

  /**
   * slackにオーダーメッセージを送信
   * @return
   */
  private def postTakeOrderMessage = {
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
        val orderStop = config.getString("tamagoya.orderStopTime")
        val takeOrderJson = Json.obj(
          "blocks" -> Json.arr(
            Json.obj(
              "type" -> "section",
              "text" -> Json.obj(
                "type" -> "mrkdwn",
                "text" -> s"注文を選択してください。 (${orderStop}〆)"
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
        case Some(json) => val url = config.getString("tamagoya.takeOrder.webhookUrl")
          ws.url(url).addHttpHeaders("Content-Type" -> "application/json")
            .withRequestTimeout(5000.millis)
            .post(json)
        case None =>
      }

    } catch {
      case e: Exception => e.printStackTrace()
    }
  }

  def acceptOrder: Action[AnyContent] = Action { request =>
    try {
      request.body.asFormUrlEncoded match {
        case Some(x) =>
          val payload = x("payload").head

          println(payload)

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
