package controllers

import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import play.api._
import play.api.mvc._
import play.api.cache.Cache
import play.api.Play.current
import play.api.db._
import play.api.libs.json.Json
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import net.ruippeixotog.scalascraper.dsl.DSL.Parse._

import scala.util.Random

object Application extends Controller {

  def index = Action {
    Ok(views.html.index(null))
  }

  def db = Action {
    var out = ""
    val conn = DB.getConnection()
    try {
      val stmt = conn.createStatement

      stmt.executeUpdate("CREATE TABLE IF NOT EXISTS ticks (tick timestamp)")
      stmt.executeUpdate("INSERT INTO ticks VALUES (now())")

      val rs = stmt.executeQuery("SELECT tick FROM ticks")

      while (rs.next) {
        out += "Read from DB: " + rs.getTimestamp("tick") + "\n"
      }
    } finally {
      conn.close()
    }
    Ok(out)
  }

  def command = Action { request =>

    try {
      request.body.asFormUrlEncoded match {
        case Some(x) =>
          val keyword = x("text").head

          val browser = JsoupBrowser()
          val doc = browser.get("https://www.irasutoya.com/search?q=" + keyword)

          val items = doc >> elementList("div.date-outer div.boxim")

          items match {
            case Nil => Ok(s"すみません！ キーワード「${keyword}」にマッチするイラストは見つかりませんでした...")
            case _ =>
              val url = items.head >> attr("href")("a")

              val doc2 = browser.get(url)

              val elem = doc2 >> element("div.date-outer")
              val imageTitleElem = elem >> element("div.title h2")
              val images = elem >> elementList("div.entry a")

              images match {
                case Nil => Ok(s"すみません！ キーワード「${keyword}」にマッチするイラストは見つかりませんでした...")
                case _ =>
                    // 複数ある場合はランダムに選択
                    val r = Random.nextInt(images.size)
                    var imageUrl: String = null
                    var index = 0

                    for (img <- images) {
                      if(index == r) imageUrl = img >> attr("src")("img")
                      index += 1
                    }

                    if (imageUrl.startsWith("//")) {
                      imageUrl = "https:" + imageUrl
                    }

                    val payload = Json.obj(
                      "response_type" -> "in_channel",
                      "text" -> imageTitleElem.text,
                      "attachments" -> Json.arr(
                        Json.obj("image_url" -> imageUrl)
                      )
                    )

                    Ok(payload).withHeaders(("Content-type", "application/json"))
              }
          }

        case None => Ok(s"すみません！ キーワードにマッチするイラストは見つかりませんでした...")
      }

    } catch {
      case e:Exception =>
        e.printStackTrace()
        Ok(s"すみません！ エラーが発生しました！")

    }
  }
}
