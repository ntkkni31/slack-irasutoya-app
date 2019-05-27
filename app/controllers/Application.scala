package controllers

import java.net.URLEncoder

import javax.inject.{Inject, Singleton}
import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import play.api._
import play.api.mvc._
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

  def index = Action {
    Ok
  }

  def command = Action { request =>

    try {
      request.body.asFormUrlEncoded match {
        case Some(x) =>
          val keyword = x("text").head
          val responseUrl = x("response_url").head
          val command = x("command").head

          if(keyword != null && keyword.length > 0) {
            respondImage(keyword, responseUrl, command)
          }

        case None =>
      }

    } catch {
      case e:Exception =>
        e.printStackTrace()
    }

    Ok
  }

  private def respondImage(keyword: String, responseUrl: String, command: String):Unit = Future {
    try {
      val browser = JsoupBrowser()
      val doc = browser.get("https://www.irasutoya.com/search?q=" + URLEncoder.encode(keyword, "UTF-8") )

      val items = doc >> elementList("div.date-outer div.boxim")

      items match {
        case Nil =>
          ws.url(responseUrl).addHttpHeaders("Content-Type" -> "application/json")
            .withRequestTimeout(5000.millis)
            .post(Json.obj("text" -> s"すみません！ キーワード「${keyword}」にマッチするイラストは見つかりませんでした..."))
        case _ =>
          val url = items.head >> attr("href")("a")

          val doc2 = browser.get(url)

          val elem = doc2 >> element("div.date-outer")
          val imageTitleElem = elem >> element("div.title h2")
          val images = elem >> elementList("div.entry a")

          images match {
            case Nil =>
              ws.url(responseUrl).addHttpHeaders("Content-Type" -> "application/json")
                .withRequestTimeout(5000.millis)
                .post(Json.obj("text" -> s"すみません！ キーワード「${keyword}」にマッチするイラストは見つかりませんでした..."))
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

              val payload = command match {
                case "/irasutoya" => Json.obj(
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
                    )
                  )
                )
                case "/irasutoya_message" => Json.obj(
                  "response_type" -> "in_channel",
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
                        "alt_text" -> imageTitleElem.text
                      )
                    )
                  )
                )
                case _ => Json.obj()
              }


              ws.url(responseUrl).addHttpHeaders("Content-Type" -> "application/json")
                .withRequestTimeout(5000.millis)
                .post(payload)
          }
      }
    } catch {
      case e:Exception => e.printStackTrace()
    }
  }
}
