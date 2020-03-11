package controllers

import java.net.URLEncoder

import javax.inject.{Inject, Singleton}
import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import play.api._
import play.api.mvc.{Action, _}
import play.api.db._
import play.api.libs.json.Json
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import net.ruippeixotog.scalascraper.dsl.DSL.Parse._
import play.api.libs.ws.WSClient

import scala.concurrent.Future
import scala.util.Random
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class Application @Inject()(cc: ControllerComponents, ws: WSClient) extends AbstractController(cc) {

  def index = Action { request =>
    println(request.headers.toMap)

    Ok
  }

  def command = Action { request =>

    try {
      request.body.asFormUrlEncoded match {
        case Some(x) =>
          val keyword = x("text").head
          val responseUrl = x("response_url").head
          val command = x("command").head
          val userId = x("user_id").head

          if(keyword != null && keyword.length > 0) {
            command match {
              case "/irasutoya" => respondImage(keyword, responseUrl, userId)
              case "/irasutoya_message" => respondMessage(keyword, responseUrl, userId)
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

  private def respondImage(keyword: String, responseUrl: String, userId: String):Unit = Future {
    try {
      val browser = JsoupBrowser()
      val doc = browser.get("https://www.irasutoya.com/search?q=" + URLEncoder.encode(keyword, "UTF-8") )

      val items = doc >> elementList("div.date-outer div.boxim")

      val messageJson = items match {
        case Nil =>
          Json.obj("text" -> s"すみません！ キーワード「$keyword」にマッチするイラストは見つかりませんでした...")
        case _ =>
          val url = items.head >> attr("href")("a")

          val doc2 = browser.get(url)

          val elem = doc2 >> element("div.date-outer")
          val imageTitleElem = elem >> element("div.title h2")
          val images = elem >> elementList("div.entry a")

          images match {
            case Nil =>
              Json.obj("text" -> s"すみません！ キーワード「$keyword」にマッチするイラストは見つかりませんでした...")
            case _ =>
              // 複数ある場合はランダムに選択
              val r = Random.nextInt(images.size)
              var imageUrl: String = null
              var index = 0

              for (img <- images) {
                if (index == r) imageUrl = img >> attr("src")("img")
                index += 1
              }

              if (imageUrl.startsWith("//")) {
                imageUrl = "https:" + imageUrl
              }

              Json.obj(
                "response_type" -> "in_channel",
                "blocks" -> Json.arr(
                  Json.obj(
                    "type" -> "image",
                    "title" -> Json.obj(
                      "type" -> "plain_text",
                      "text" -> imageTitleElem.text,
                      "emoji" -> true
                    ),
                    "image_url" -> imageUrl,
                    "alt_text" -> imageTitleElem.text
                  ),
                  Json.obj(
                    "type" -> "context",
                    "elements" -> Json.arr(
                      Json.obj(
                        "type" -> "mrkdwn",
                        "text" -> s"Author: <@$userId>"
                      )
                    )
                  )
                )
              )
          }
      }

      ws.url(responseUrl).addHttpHeaders("Content-Type" -> "application/json")
        .withRequestTimeout(5000.millis)
        .post(messageJson)

    } catch {
      case e:Exception => e.printStackTrace()
    }
  }

  private def respondMessage(keyword: String, responseUrl: String, userId: String):Unit = Future {
    try {
      ws.url("https://wy59x9hce3.execute-api.ap-northeast-1.amazonaws.com/Prod/search?keyword=" + URLEncoder.encode(keyword, "UTF-8"))
        .withRequestTimeout(5000.millis)
        .get()
        .map {
          response =>
            val messageJson = if ((response.json \ "hits" \ "total").as[Int] > 0) {
              val source = response.json \ "hits" \ "hits" \ 0 \ "_source"
              var imageUrl = (source \ "image").as[String]
              val imageTitle = (source \ "title").as[String]

              imageUrl = imageUrl.replaceAll("/s1/", "/s256/")

              Json.obj(
                "response_type" -> "in_channel",
                "as_user" -> true,
                "user_id" -> userId,
                "blocks" -> Json.arr(
                  Json.obj(
                    "type" -> "section",
                    "text" -> Json.obj(
                      "type" -> "mrkdwn",
                      "text" -> keyword
                    ),
                    "accessory" -> Json.obj(
                      "type" -> "image",
                      "image_url" -> imageUrl,
                      "alt_text" -> imageTitle
                    )
                  )
                )
              )
            } else {
              Json.obj("text" -> s"すみません！ キーワード「$keyword」にマッチするイラストは見つかりませんでした...")
            }

            ws.url(responseUrl).addHttpHeaders("Content-Type" -> "application/json")
              .withRequestTimeout(5000.millis)
              .post(messageJson)
        }
    } catch {
      case e: Exception => e.printStackTrace()
    }
  }

  def event = Action { request =>
    val json = request.body.asJson

    println(json.get.toString())

    Ok(json.get.toString())
  }
}
