package controllers.tamagoya

import com.typesafe.config.ConfigFactory
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsArray, JsObject, JsSuccess, JsValue, Json}
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import com.sendgrid.{Method, Request, SendGrid}
import com.sendgrid.helpers.mail.Mail
import com.sendgrid.helpers.mail.objects.{Content, Email}
import org.joda.time.{DateTime, DateTimeZone}
import play.api.cache.{NamedCache, SyncCacheApi}
import play.api.db.Database

import scala.concurrent.Await

@Singleton
class TamagoyaApp @Inject()(cc: ControllerComponents,
                            ws: WSClient,
                            @NamedCache("slackUserCache") userNameCache: SyncCacheApi,
                            @NamedCache("publicHolidayCache") publicHolidayCache: SyncCacheApi,
                            db: Database) extends AbstractController(cc) {

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
              postTakeOrderMessage()
            }

            Ok
        }
      case None => NoContent
    }
  }

  /**
   * オーダーメッセージを送信するトリガー判定
   * @param json jsonオブジェクト
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
      println(json.toString())
    }

    // 特定のボットが特定のメッセージを送信したら
    if (channel == config.getString("tamagoya.channel") && botId == config.getString("tamagoya.todayBot")) {
      (json \ "event" \ "text").validate[String] match {
        case JsSuccess(text, _) => text.contains("本日のたまごや")
        case _ =>
          (json \ "event" \ "attachments").validate[JsValue] match {
            case JsSuccess(attachments, _) =>
              println("attachments.toString(): " + attachments.toString())
              attachments.toString().contains("本日のたまごや")
            case _ => false
          }
      }
    } else {
      false
    }
  }

  /**
   * slackにオーダーメッセージを送信
   * @return
   */
  private def postTakeOrderMessage(url: String = config.getString("tamagoya.takeOrder.webhookUrl")) = {
    try {
      val now = DateTime.now(DateTimeZone.forID("Asia/Tokyo"))

      val publicHoliday = getOrUpdatePublicHoliday(now)

      val messageJson = if (now.getDayOfWeek < 1 || 5 < now.getDayOfWeek) {
        val text = s"本日は *休日* です。"
        val holidayJson = Json.obj(
          "text" -> text,
          "blocks" -> Json.arr(
            Json.obj(
              "type" -> "section",
              "text" -> Json.obj(
                "type" -> "mrkdwn",
                "text" -> text
              )
            )
          )
        )

        Option(holidayJson)
      } else if (publicHoliday.nonEmpty) {
        val text = s"本日は *${publicHoliday.get}* です。"
        val publicHolidayJson = Json.obj(
          "text" -> text,
          "blocks" -> Json.arr(
            Json.obj(
              "type" -> "section",
              "text" -> Json.obj(
                "type" -> "mrkdwn",
                "text" -> text
              )
            )
          )
        )

        Option(publicHolidayJson)
      } else {
        val orderStop = config.getString("tamagoya.orderStopTime")
        val text = s"注文を選択してください。 (${orderStop}〆)"

        val takeOrderJson = Json.obj(
          "response_type" -> "in_channel",
          "text" -> text,
          "blocks" -> Json.arr(
            Json.obj(
              "type" -> "section",
              "text" -> Json.obj(
                "type" -> "mrkdwn",
                "text" -> text
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

        Option(takeOrderJson)
      }

      messageJson match {
        case Some(json) =>
          ws.url(url).addHttpHeaders("Content-Type" -> "application/json")
            .withRequestTimeout(5000.millis)
            .post(json)
            .map(response => {
              (response.json \ "ok").validate[Boolean] match {
                case JsSuccess(ok, _) if ok =>
                case _ => println(response.json) // エラー("ok":true 以外)のときだけログに出す
              }
            })
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
          val channelName = (json \ "channel" \ "name").as[String]
          val messageTs = (json \ "container" \ "message_ts").as[String]
          val channelId = (json \ "container" \ "channel_id").as[String]
          val order = (json \ "actions" \ 0 \ "value").as[String]
          val orderText = (json \ "actions" \ 0 \ "text" \ "text").as[String]

          val realName = userNameCache.get[String](userId) match {
            case Some(rn) => rn
            case None => updateAndGetUserNameCache(userId).orNull
          }

          val text = try {
            val now = DateTime.now(DateTimeZone.forID("Asia/Tokyo"))
            val messageDate = new DateTime(new java.util.Date(messageTs.split('.')(0).toLong * 1000), DateTimeZone.forID("Asia/Tokyo"))
            println(now)
            println(messageDate)

            val orderStop = config.getString("tamagoya.orderStopTime").split(':')
            val orderStopMinutes = orderStop(0).toInt * 60 + orderStop(1).toInt

            if (messageDate.getDayOfYear != now.getDayOfYear || now.getMinuteOfDay > orderStopMinutes) { // TODO 別の日チェックはちゃんとやる
              s"<@$userId> 本日の注文は締め切られました。"
            } else {

              // メール送信
              val mailJson = Json.obj(
                "date" -> now.toString("yyyy-MM-dd HH:mm:ss"),
                "username" -> userName,
                "real_name" -> realName,
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
              val nameForMessage = if(realName == null) userName else realName
              if (order == "cancel") s"$nameForMessage がキャンセルしました。" else s"$nameForMessage が *$orderText* を注文しました。"
            }
          } catch {
            case ex: Exception =>
              ex.printStackTrace()

              s"<@$userId> 予期せぬエラーが発生しました。"

          }

          if (channelName == "directmessage") {
            // TODO ダイレクトメッセージの判定はconversations.listでちゃんとやるべきだが
            val res = Json.obj(
              //"as_user" -> false,
              "username" -> "Tamagoya/Lunch Order",
              //"user_id" -> userId,
              "text" -> text,
              "blocks" -> Json.arr(
                Json.obj(
                  "type" -> "section",
                  "text" -> Json.obj(
                    "type" -> "mrkdwn",
                    "text" -> text
                  )
                )
              )
            )

            ws.url(responseUrl)
              .addHttpHeaders("Content-Type" -> "application/json")
              .withRequestTimeout(5000.millis)
              .post(res)
              .map(response => {
                (response.json \ "ok").validate[Boolean] match {
                  case JsSuccess(ok, _) if ok =>
                  case _ => println(response.json) // エラー("ok":true 以外)のときだけログに出す
                }
              })
          } else {
            // ボタンのレスポンスにすると一人しか受け付けられないので、スレッドでリプライしておく
            val res = Json.obj(
              "channel" -> channelId,
              "thread_ts" -> messageTs,
              //"as_user" -> false,
              "username" -> "Tamagoya/Lunch Order",
              //"user_id" -> userId,
              "text" -> text,
              "blocks" -> Json.arr(
                Json.obj(
                  "type" -> "section",
                  "text" -> Json.obj(
                    "type" -> "mrkdwn",
                    "text" -> text
                  )
                )
              )
            )

            ws.url("https://slack.com/api/chat.postMessage")
              .addHttpHeaders("Content-Type" -> "application/json",
                "Authorization" -> s"Bearer ${config.getString("tamagoya.botAuthToken")}")
              .withRequestTimeout(5000.millis)
              .post(res)
              .map(response => {
                (response.json \ "ok").validate[Boolean] match {
                  case JsSuccess(ok, _) if ok =>
                  case _ => println(response.json) // エラー("ok":true 以外)のときだけログに出す
                }
              })
          }

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
   * @param date 判定対象の日時オブジェクト
   * @return
   */
  def getOrUpdatePublicHoliday(date: DateTime): Option[String] = {
    // 祝日はまあよっぽど変わらないのでsynchronizedにしなくてもいいか
    try {
      publicHolidayCache.get(date.toString("yyyy-01-01")) match {
        case Some(_) => // 1月1日があるということは、他のキャッシュ値もあるということ
        case None =>

          val f = ws.url("https://holidays-jp.github.io/api/v1/date.json")
            .withRequestTimeout(5000.millis)
            .get()
            .map {
              response =>

                for (elem <- response.json.as[JsObject].fields) {
                  publicHolidayCache.set(elem._1, elem._2.as[String])
                  //println(elem)
                }
            }

          Await.result(f, Duration.Inf)
      }

      publicHolidayCache.get(date.toString("yyyy-MM-dd"))
    } catch {
      case e:Exception => e.printStackTrace()
        None
    }
  }

  private def updateAndGetUserNameCache(userId: String) = synchronized {
    try {
      val f = ws.url(s"https://slack.com/api/users.list?token=${config.getString("tamagoya.botAuthToken")}")
        .addHttpHeaders("Content-Type" -> "application/x-www-form-urlencoded")
        .withRequestTimeout(5000.millis)
        .get()
        .map {
          response =>
            (response.json \ "members").as[JsArray].value.foreach(m => {

              val id = (m \ "id").validate[String]
              val name = (m \ "name").validate[String]
              val realName = (m \ "real_name").validate[String]

              if (id.isSuccess && name.isSuccess && realName.isSuccess){
                userNameCache.set(id.get, realName.get)
              }
            })

            userNameCache.get(userId)
        }

      Await.result(f, Duration.Inf)
    } catch {
      case e:Exception => e.printStackTrace()
        None
    }
  }

  /**
   * コマンド叩いても動かせるように
   * @return
   */
  def command:Action[AnyContent] = Action { request =>
    try {
      request.body.asFormUrlEncoded match {
        case Some(x) =>
          val arg = x("text").head
          val responseUrl = x("response_url").head
          val command = x("command").head
          val userId = x("user_id").head

          if(arg != null && arg.length > 0) {
            command match {
              case "/tamagoya" =>
                arg match {
                  case "order" =>
                    postTakeOrderMessage(responseUrl)
                  case _ =>
                }
              case _ =>
            }
          }

        case None =>
      }

    } catch {
      case e:Exception => e.printStackTrace()
    }

    Ok
  }

  private def updateOrderDb(date: DateTime, username: String, order: String) = {
    try {
      db.withConnection { implicit c =>

        val stmt = c.createStatement()
        val rs = stmt.executeQuery("UPDATE order_list SET ()")

      }

    } catch {
      case e: Exception => e.printStackTrace()
    }
  }
}
