package org.allenai.srlie

import edu.knowitall.srlie.confidence.SrlConfidenceFunction
import edu.knowitall.srlie.confidence.SrlFeatureSet
import edu.knowitall.srlie.SrlExtractor
import edu.knowitall.tool.parse.ClearParser
import edu.knowitall.tool.parse.graph.DependencyGraph
import edu.knowitall.tool.parse.RemoteDependencyParser
import edu.knowitall.tool.srl.ClearSrl
import edu.knowitall.tool.srl.RemoteSrl
import edu.knowitall.tool.stem.MorphaStemmer

import akka.actor._
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import spray.http._
import spray.routing._

import scala.concurrent.ExecutionContext.global
import scala.util.Try

class SrlieServer(port: Int, remoteParser: Option[String], remoteSrl: Option[String]) extends SimpleRoutingApp {
  val logger = LoggerFactory.getLogger(this.getClass)

  val parser = remoteParser match {
    case Some(url) =>
      logger.info("Using remote parser at: " + url)
      new RemoteDependencyParser(url)(global)
    case None =>
      logger.info("Creating new ClearParser.")
      new ClearParser()
  }

  val srl = remoteSrl match {
    case Some(url) =>
      logger.info("Using remote srl at: " + url)
      new RemoteSrl(url)(global)
    case None =>
      logger.info("Creating new ClearSrl.")
      new ClearSrl()
  }

  val srlie = new SrlExtractor(srl)
  val metric = SrlConfidenceFunction.fromUrl(SrlFeatureSet, SrlConfidenceFunction.defaultModelUrl)

  def run() {
    val cacheControlMaxAge = HttpHeaders.`Cache-Control`(CacheDirectives.`max-age`(60))

    implicit val system = ActorSystem("srlie-server")

    val info = Map(
        "name" -> "srlie",
        "description" -> "The primary component of Open IE 4.0."
      )

    startServer(interface = "0.0.0.0", port = port) {
      respondWithHeader(cacheControlMaxAge) {
        path ("") {
          get {
            complete("Post to extract with srlie.")
          } ~
          post {
            entity(as[String]) { sentence =>
              srlie.synchronized {
                logger.info("Processing: " + sentence)
                val (tokens, dgraph) = parser(sentence)

                logger.debug("dgraph: " + DependencyGraph.singlelineStringFormat.write(dgraph))

                // Run srlie.
                val insts = srlie(tokens map MorphaStemmer.lemmatizePostaggedToken, dgraph)
                logger.debug("extrs: " + (insts map (_.extr)))

                // Match extractions with confidence and sort.
                val extrs = (insts map (inst => (metric(inst), inst.extr))).sortBy(-_._1)

                val formatted = extrs map { case (conf, extr) => f"$conf%.3f $extr" }
                complete(formatted mkString "\n")
              }
            }
          }
        } ~
        path("info") {
          complete {
            info.keys mkString "\n"
          }
        } ~
        path("info" / Segment) { key =>
          complete {
            info.get(key) match {
              case Some(key) => key
              case None => (StatusCodes.NotFound, "Could not find info: " + key)
            }
          }
        }
      }
    }
  }
}

object SrlieServerMain extends App {
  val config = ConfigFactory.load().getConfig("srlie.server")
  val port = config.getInt("port")
  val remoteParser = Try(Option(config.getString("remote.parser"))).toOption.flatten
  val remoteSrl = Try(Option(config.getString("remote.srl"))).toOption.flatten

  val server = new SrlieServer(port, remoteParser, remoteSrl)
  server.run()
}