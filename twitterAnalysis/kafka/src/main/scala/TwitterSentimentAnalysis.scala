import java.util.Properties
import org.apache.spark.SparkConf
import org.apache.spark.sql.SQLContext
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.streaming.twitter.TwitterUtils
import org.apache.spark.streaming.{Seconds, StreamingContext}
import edu.stanford.nlp.ling.CoreAnnotations
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations
import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations
import org.apache.spark.storage._
import scala.collection.convert.wrapAll._


object Analysis extends Enumeration {
  type Sentiment                  = Value
  val POSITIVE, NEGATIVE, NEUTRAL = Value

  def toSentiment(sentiment: Int): Sentiment = sentiment match {
    case x if x == 0 || x == 1 => Analysis.NEGATIVE
    case 2 => Analysis.NEUTRAL
    case x if x == 3 || x == 4 => Analysis.POSITIVE
  }
}
object SentimentAnalysis {
  val properties = new Properties()
  properties.setProperty("annotators", "tokenize, ssplit, parse, sentiment")
  val pipeline: StanfordCoreNLP = new StanfordCoreNLP(properties)

  def mainSentiment(input: String): Analysis.Sentiment = Option(input) match {
    case Some(text) if !text.isEmpty => extractSentiment(text)
    case _ => throw new IllegalArgumentException("input can't be null or empty")
  }

  private def extractSentiment(text: String): Analysis.Sentiment = {
    val (_, sentiment) = extraction(text)
      .maxBy { case (tweets, _) => tweets.length }
    sentiment
  }

  def extraction(text: String): List[(String, Analysis.Sentiment)] = {
    val annotation: Annotation = pipeline.process(text)
    val tweetSentences         = annotation.get(classOf[CoreAnnotations.SentencesAnnotation])
    tweetSentences
      .map(tweets => (tweets, tweets.get(classOf[SentimentCoreAnnotations.SentimentAnnotatedTree])))
      .map { case (tweets, tree) => (tweets.toString,Analysis.toSentiment(RNNCoreAnnotations.getPredictedClass(tree))) }
      .toList
  }
}

object TwitterSentimentAnalysis {
  def main(args: Array[String]): Unit = {
    val sparkConf = new SparkConf().setAppName("assignment3").setMaster("local[4]").set("spark.driver.host", "localhost")

    if (args.length < 5) {
      println("Invalid number of arguements")
      System.exit(1)
    }

    val topic = args(0)
    val log   = Logger.getRootLogger()
    log.setLevel(Level.WARN)

    val APIKey  = args(1);val APISecret = args(2);val token = args(3);val tokenSecret = args(4)
    val filters = Seq("Biden", "Trump", "Election", "President")
    System.setProperty("twitter4j.oauth.consumerKey", APIKey)
    System.setProperty("twitter4j.oauth.consumerSecret", APISecret)
    System.setProperty("twitter4j.oauth.accessToken", token)
    System.setProperty("twitter4j.oauth.accessTokenSecret", tokenSecret)

    @transient val streamContext = new StreamingContext(sparkConf, Seconds(2))
    val tweets       = TwitterUtils.createStream(streamContext, None, filters, StorageLevel.MEMORY_ONLY_SER_2)
    val en_tweets    = tweets.filter(_.getLang() == "en")
    val tweet_status = en_tweets.map(status => (status.getText()))

    tweet_status.foreachRDD { (rdd, time) =>
      rdd.foreachPartition { partitionIter =>
        val properties     = new Properties()
        val bootstrap_host = "localhost:9092"
        properties.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        properties.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        properties.put("bootstrap.servers", bootstrap_host)
        val kafkaP         = new KafkaProducer[String, String](properties)

        partitionIter.foreach { element =>
          val data     = element.toString()
          val analysis = SentimentAnalysis.mainSentiment(data).toString()
          val output   = new ProducerRecord[String, String](topic, "sentiment", analysis + "->" +  data)
          kafkaP.send(output)
        }
        kafkaP.flush()
        kafkaP.close()
      }
    }
    streamContext.start()
    streamContext.awaitTermination()
  }
}