package passim

import org.apache.spark.SparkConf
import org.apache.spark.graphx._
import org.apache.spark.sql.{SparkSession, DataFrame, Row}
import org.apache.spark.sql.functions._
import org.apache.spark.storage.StorageLevel

import org.apache.hadoop.fs.{FileSystem,Path}

import collection.JavaConversions._
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.util.Try

import java.security.MessageDigest
import java.nio.ByteBuffer
import jaligner.Sequence

case class Config(version: String = BuildInfo.version,
  boilerplate: Boolean = false,
  n: Int = 5, minDF: Int = 2, maxDF: Int = 100, minRep: Int = 5, minAlg: Int = 20,
  gap: Int = 100, relOver: Double = 0.8, maxRep: Int = 10,
  wordLength: Double = 2,
  pairwise: Boolean = false, duppairs: Boolean = false,
  docwise: Boolean = false, names: Boolean = false, postings: Boolean = false,
  id: String = "id", group: String = "series", text: String = "text",
  fields: String = "",  filterpairs: String = "gid < gid2",
  inputFormat: String = "json", outputFormat: String = "json",
  inputPaths: String = "", outputPath: String = "")

case class Coords(x: Int, y: Int, w: Int, h: Int, b: Int) {
  def x2 = x + w
  def y2 = y + h
  def merge(that: Coords): Coords = {
    val xnew = Math.min(x, that.x)
    val ynew = Math.min(y, that.y)
    Coords(xnew, ynew,
      Math.max(this.x2, that.x2) - xnew,
      Math.max(this.y2, that.y2) - ynew,
      Math.max(this.y2, that.y2) - ynew)
  }
}

case class Region(start: Int, length: Int, coords: Coords) {
  def end = start + length
  def offset(off: Int) = Region(this.start + off, this.length, this.coords)
}

case class Page(id: String, seq: Int, width: Int, height: Int, dpi: Int, regions: Array[Region])

case class Locus(start: Int, length: Int, loc: String) {
  def end = start + length
  def offset(off: Int) = Locus(this.start + off, this.length, this.loc)
}

case class DocSpan(uid: Long, begin: Int, end: Int, first: Boolean)

// Could parameterized on index type instead of Int
case class Span(val begin: Int, val end: Int) {
  def length = end - begin
  def size = this.length
  def union(that: Span): Span = {
    Span(Math.min(this.begin, that.begin), Math.max(this.end, that.end))
  }
  def intersect(that: Span): Span = {
    val res = Span(Math.max(this.begin, that.begin), Math.min(this.end, that.end))
    if ( res.begin >= res.end ) Span(0, 0) else res
  }
}

case class Post(feat: Long, tf: Int, post: Int)

case class IdSeries(id: Long, series: Long)

case class PassAlign(id1: String, id2: String,
  s1: String, s2: String, b1: Int, e1: Int, n1: Int, b2: Int, e2: Int, n2: Int,
  matches: Int, score: Float)

case class AlignedStrings(s1: String, s2: String, matches: Int, score: Float)

case class NewDoc(id: String, text: String, pages: Seq[Page], aligned: Boolean)

object PassFun {
  def increasingMatches(matches: Iterable[(Int,Int,Int)]): Array[(Int,Int,Int)] = {
    val in = matches.toArray.sorted
    val X = in.map(_._2).toArray
    val N = X.size
    var P = Array.fill(N)(0)
    var M = Array.fill(N + 1)(0)
    var L = 0
    for ( i <- 0 until N ) {
      var low = 1
      var high = L
      while ( low <= high ) {
	val mid = Math.ceil( (low + high) / 2).toInt
	if ( X(M(mid)) < X(i) )
	  low = mid + 1
	else
	  high = mid - 1
      }
      val newL = low
      P(i) = M(newL - 1)
      M(newL) = i
      if ( newL > L ) L = newL
    }
    // Backtrace
    var res = Array.fill(L)((0,0,0))
    var k = M(L)
    for ( i <- (L - 1) to 0 by -1 ) {
      res(i) = in(k)
      k = P(k)
    }
    res.toArray
  }

  def gappedMatches(n: Int, gapSize: Int, minAlg: Int, matches: Array[(Int, Int, Int)]) = {
    val N = matches.size
    var i = 0
    var res = new ListBuffer[((Int,Int), (Int,Int))]
    for ( j <- 0 until N ) {
      val j1 = j + 1
      if ( j == (N-1) || (matches(j1)._1 - matches(j)._1) > gapSize || (matches(j1)._2 - matches(j)._2) > gapSize) {
	// This is where we'd score the spans
	if ( j > i && (matches(j)._1 - matches(i)._1 + n - 1) >= minAlg
	     && (matches(j)._2 - matches(i)._2 + n - 1) >= minAlg) {
	  res += (((matches(i)._1, matches(j)._1 + n - 1),
		   (matches(i)._2, matches(j)._2 + n - 1)))
	}
	i = j1
      }
    }
    res.toList
  }

  // TODO: The right thing to do is to force the alignment to go
  // through the right or left corner, but for now we hack it by
  // padding the left or right edge with duplicate text.
  def alignEdge(matchMatrix: jaligner.matrix.Matrix,
    idx1: Int, idx2: Int, text1: String, text2: String, anchor: String) = {
    var (res1, res2) = (idx1, idx2)
    val pad = " this text is long and should match "
    val ps = pad count { _ == ' ' }
    val t1 = if ( anchor == "L" ) (pad + text1) else (text1 + pad)
    val t2 = if ( anchor == "L" ) (pad + text2) else (text2 + pad)
    val alg = jaligner.SmithWatermanGotoh.align(new Sequence(t1), new Sequence(t2),
      matchMatrix, 5.0f, 0.5f)
    val s1 = alg.getSequence1()
    val s2 = alg.getSequence2()
    val len1 = s1.size - s1.count(_ == '-')
    val len2 = s2.size - s2.count(_ == '-')
    val extra = alg.getIdentity() - pad.size
    if ( s1.size > 0 && s2.size > 0 && extra > 2 ) {
      if ( anchor == "L" ) {
        if ( alg.getStart1() == 0 && alg.getStart2() == 0 ) {
          res1 += s1.count(_ == ' ') - (if (s1(s1.size - 1) == ' ') 1 else 0) - ps + 1
          res2 += s2.count(_ == ' ') - (if (s2(s2.size - 1) == ' ') 1 else 0) - ps + 1
        }
      } else if ( anchor == "R" ) {
        if ( alg.getStart1() + len1 >= t1.size && alg.getStart2() + len2 >= t2.size ) {
          res1 -= s1.count(_ == ' ') - (if (s1(0) == ' ') 1 else 0) - ps + 1
          res2 -= s2.count(_ == ' ') - (if (s2(0) == ' ') 1 else 0) - ps + 1
        }
      }
    }
    (res1, res2)
  }

  // HACK: This is only guaranteed to work when rover == 0.
  def mergeSpansLR(rover: Double, init: Iterable[(Span, Long)]): Seq[(Span, ArrayBuffer[Long])] = {
    val res = ArrayBuffer[(Span, ArrayBuffer[Long])]()
    val in = init.toArray.sortWith((a, b) => a._1.begin < b._1.begin)
    for ( cur <- in ) {
      val span = cur._1
      val cdoc = ArrayBuffer(cur._2)
      if ( res.size == 0 ) {
        res += ((span, cdoc))
      } else {
        val top = res.last._1
        if ( (1.0 * span.intersect(top).length / span.union(top).length) > rover ) {
          val rec = ((span.union(top), res.last._2 ++ cdoc))
          res(res.size - 1) = rec
        } else {
          res += ((span, cdoc))
        }
      }
    }
    res.toSeq
  }

  def mergeSpans(rover: Double, init: Iterable[(Span, Long)]): Seq[(Span, ArrayBuffer[Long])] = {
    val res = ArrayBuffer[(Span, ArrayBuffer[Long])]()
    val in = init.toArray.sortWith((a, b) => a._1.length < b._1.length)
    for ( cur <- in ) {
      val span = cur._1
      var idx = -1
      var best = 0.0
      for ( i <- 0 until res.size ) {
        val s = res(i)._1
        val score = 1.0 * span.intersect(s).length / span.union(s).length
        if ( score > rover && score > best ) {
          idx = i
          best = score
        }
      }
      if ( idx < 0 ) {
        res += ((span, ArrayBuffer(cur._2)))
      } else {
        val rec = ((res(idx)._1.union(span), res(idx)._2 ++ ArrayBuffer(cur._2)))
        res(idx) = rec
      }
    }
    res.toSeq
  }

  def hapaxIndex(n: Int, wordLength: Double, w: Seq[String]) = {
    val minFeatLen: Double = wordLength * n
    w.sliding(n)
      .zipWithIndex
      .filter { _._1.map(_.size).sum >= minFeatLen }
      .map { case (s, pos) => (s.mkString("~").##, pos) }
      .toArray
      .groupBy(_._1)
      .mapValues(_.map(_._2))
      .filter(_._2.size == 1)
      .mapValues(_(0))
  }
  def hapaxIndex(n: Int, w: Seq[String]): Map[Int, Int] = hapaxIndex(n, 2, w)
  def hapaxIndex(n: Int, s: String) = {
    s.sliding(n)
      .zipWithIndex
      .toArray
      .groupBy(_._1)
      .mapValues(_.map(_._2))
      .filter(_._2.size == 1)
      .mapValues(_(0))
  }

  case class AlignedPassage(s1: String, s2: String, b1: Int, b2: Int, matches: Int, score: Float)

  def recursivelyAlignStrings(s1: String, s2: String,
    n: Int, gap2: Int, matchMatrix: jaligner.matrix.Matrix,
    openGap: Float, contGap: Float): Seq[AlignedPassage] = {
    val m1 = hapaxIndex(n, s1)
    val m2 = hapaxIndex(n, s2)
    val inc = increasingMatches(m1
      .flatMap(z => if (m2.contains(z._1)) Some((z._2, m2(z._1), 1)) else None))
    val prod = s1.size * s2.size
    if ( inc.size == 0 && (prod >= gap2 || prod < 0) ) {
      Seq(AlignedPassage(s1 + ("-" * s2.size), ("-" * s1.size) + s2,
        0, 0, 0, -openGap - contGap * s1.size - contGap * s2.size))
    } else {
      (Array((0, 0, 0)) ++ inc ++ Array((s1.size, s2.size, 0)))
        .sliding(2).flatMap(z => {
          val (b1, b2, c) = z(0)
          val (e1, e2, _) = z(1)
          val n1 = e1 - b1
          val n2 = e2 - b2
          val chartSize = n1 * n2
          if ( c == 0 && e1 == 0 && e2 == 0 ) {
            Seq()
          } else if ( chartSize <= gap2 && chartSize >= 0 ) { // overflow!
            val p1 = s1.substring(b1, e1)
            val p2 = s2.substring(b2, e2)
            if ( n1 == n2 && p1 == p2 ) {
              Seq(AlignedPassage(p1, p2, b1, b2, p1.size, 2.0f * p2.size))
            } else {
              val alg = jaligner.NeedlemanWunschGotoh.align(new jaligner.Sequence(p1),
                new jaligner.Sequence(p2), matchMatrix, openGap, contGap)
              // // HACK!! WHY does JAligner swap sequences ?!?!?!?
              val a1 = new String(alg.getSequence2)
              val a2 = new String(alg.getSequence1)
              if ( a1.replaceAll("-", "") == p2 && a2.replaceAll("-", "") == p1 ) {
                Seq(AlignedPassage(a2, a1, b1, b2, alg.getIdentity, alg.getScore))
              } else {
                Seq(AlignedPassage(a1, a2, b1, b2, alg.getIdentity, alg.getScore))
              }
            }
          } else {
            if ( c > 0 ) {
              val len = Math.min(n, Math.min(n1, n2))
              val p1 = s1.substring(b1, b1 + len)
              val p2 = s2.substring(b2, b2 + len)
              Array(AlignedPassage(p1, p2, b1, b2, len, 2.0f * len)) ++
              recursivelyAlignStrings(s1.substring(b1 + len, e1), s2.substring(b2 + len, e2), n, gap2, matchMatrix, openGap, contGap)
            } else {
              recursivelyAlignStrings(s1.substring(b1, e1), s2.substring(b2, e2), n, gap2, matchMatrix, openGap, contGap)
            }
          }
        }).toSeq
    }
  }
}

case class TokText(terms: Array[String], termCharBegin: Array[Int], termCharEnd: Array[Int])

object PassimApp {
  def hashString(s: String): Long = {
    ByteBuffer.wrap(
      MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"))
    ).getLong
  }
  def rowToRegion(r: Row): Region = {
    r match {
      case Row(start: Int, length: Int, coords: Row) =>
        coords match {
          case Row(x: Int, y: Int, w: Int, h: Int, b: Int) =>
            Region(start, length, Coords(x, y, w, h, b))
        }
    }
  }
  def rowToPage(r: Row): Page = {
    r match {
      case Row(id: String, seq: Int, width: Int, height: Int, dpi: Int, regions: Seq[_]) =>
        Page(id, seq, width, height, dpi, regions.asInstanceOf[Seq[Row]].map(rowToRegion).toArray)
    }
  }
  def rowToLocus(r: Row): Locus = {
    r match {
      case Row(start: Int, length: Int, loc: String) => Locus(start, length, loc)
    }
  }
  implicit class TextTokenizer(df: DataFrame) {
    val tokenizeCol = udf {(text: String) =>
      val tok = new passim.TagTokenizer()

      var d = new passim.Document("raw", text)
      tok.tokenize(d)

      TokText(d.terms.toSeq.toArray,
        d.termCharBegin.map(_.toInt).toArray,
        d.termCharEnd.map(_.toInt).toArray)
    }
    def tokenize(colName: String): DataFrame = {
      if ( df.columns.contains("terms") ) {
        df
      } else {
        df.withColumn("_tokens", tokenizeCol(col(colName)))
          .withColumn("terms", col("_tokens")("terms"))
          .withColumn("termCharBegin", col("_tokens")("termCharBegin"))
          .withColumn("termCharEnd", col("_tokens")("termCharEnd"))
          .drop("_tokens")
      }
    }
    val boundLoci = udf {(begin: Int, end: Int, loci: Seq[Row]) =>
      Try(loci.map(rowToLocus)
        .filter { r => r.start <= end && r.end >= begin }
        .map { _.loc }
        .distinct.sorted) // stable
        .getOrElse(Seq[String]())
    }
    val pageRegions = udf{(begin: Int, end: Int, pages: Seq[Row]) =>
      Try(pages.map(rowToPage)
        .flatMap { p =>
        val overlap = p.regions.filter { r => r.start <= end && r.end >= begin }
        if ( overlap.size > 0 )
          Some(p.copy(regions = Array(Region(overlap.head.start,
            overlap.last.end - overlap.head.start,
            overlap.map(_.coords).reduce(_.merge(_))))))
        else
          None
      }
      )
        .getOrElse(Seq[Page]())
    }
    def selectRegions(pageCol: String): DataFrame = {
      if ( df.columns.contains(pageCol) ) {
        df.withColumn(pageCol, pageRegions(col("begin"), col("end"), col(pageCol)))
      } else {
        df
      }
    }
    def selectLocs(colName: String): DataFrame = {
      if ( df.columns.contains(colName) ) {
        df.withColumn(colName, boundLoci(col("begin"), col("end"), col(colName)))
      } else {
        df
      }
    }
  }

  def makeIndexer(n: Int, wordLength: Double) = {
    val minFeatLen: Double = wordLength * n
    udf { (terms: Seq[String]) =>
      terms.sliding(n)
        .zipWithIndex
        .filter { _._1.map(_.size).sum >= minFeatLen }
        .map { case (s, pos) => (hashString(s.mkString("~")), pos) }
        .toArray
        .groupBy(_._1)
      // Store the count and first posting; could store
      // some other fixed number of postings.
        .map { case (feat, post) => Post(feat, post.size, post(0)._2) }
        .toSeq
    }
  }

  val alignedPassages = udf { (s1: String, s2: String) =>
    var start = 0
    var b1 = 0
    var b2 = 0
    val buf = ArrayBuffer[(Int, Double, Int, Int, String, String)]()
    for ( end <- 1 until s2.size ) {
      if ( s2(end) == '\n' ) {
        val alg1 = s1.substring(start, end+1)
        val alg2 = s2.substring(start, end+1)
        val t1 = alg1.replaceAll("-", "").replaceAll("\u2010", "-")
        val t2 = alg2.replaceAll("-", "").replaceAll("\u2010", "-")

        val matches = alg1.zip(alg2).count(x => x._1 == x._2)
        buf += ((t2.size - t1.size, matches * 1.0 / t2.size, b1, b2, t1, t2))
        start = end + 1
        b1 += t1.size
        b2 += t2.size
      }
    }
    val lines = buf.toArray

    val minLines = 5

    val pass = ArrayBuffer[(Span, Span, Array[(String, String)])]()
    val pairs = ArrayBuffer[(String, String)]()
    var i = 0
    start = 0
    while ( i < lines.size ) {
      if ( lines(i)._1.abs > 20 || lines(i)._2 < 0.1 ) {
        if ( start < i
          && (i + 2) < lines.size
          && lines(i+1)._1.abs <= 20 && lines(i+1)._2 >= 0.1
          && (lines(i+1)._3 - lines(i)._3) <= 20
          && (lines(i+1)._4 - lines(i)._4) <= 20 ) {
          // continue passage
          pairs += ((lines(i)._5, lines(i)._6))
        } else {
          if ( (i - start) >= minLines ) {
            pass += ((Span(lines(start)._3, lines(i)._3),
              Span(lines(start)._4, lines(i)._4),
              pairs.toArray))
          }
          start = i + 1
          pairs.clear
        }
      } else {
        pairs += ((lines(i)._5, lines(i)._6))
      }
      i += 1
    }
    if ( (i - start) >= minLines ) {
      pass += ((Span(lines(start)._3, lines(lines.size - 1)._3),
        Span(lines(start)._4, lines(lines.size - 1)._4),
        pairs.toArray))
    }
    pass.toSeq
  }

  val mergeAligned = udf { (begins: Seq[Int], ends: Seq[Int]) =>
    val spans = PassFun.mergeSpansLR(0, begins.zip(ends).map(x => Span(x._1, x._2))
      .zip(Range(0, begins.size).map(_.toLong)))
      .map(_._1) // TODO? merge nearly adjacent?
    (spans.map(_.begin), spans.map(_.end)) // unzip
  }

  def subpage(p: Seq[Page], r: Array[Region]) = {
    if ( p.size > 0 )
      Seq(p(0).copy(regions = r))
    else
      p
  }

  val splitDoc = udf { (id: String, text: String, pages: Seq[Row],
    begin: Seq[Int], end: Seq[Int]) =>
    val pp
      = if ( pages == null ) Array[Page]() // Try doesn't catch nulls
      else Try(pages.map(PassimApp.rowToPage).toArray).getOrElse(Array[Page]())
    val reg = if ( pp.size == 0 ) Array[Region]() else pp(0).regions
    val docs = new ArrayBuffer[NewDoc]
    if ( begin == null || begin.size <= 0 ) {
      docs += NewDoc(id, text, pp, false)
    } else {
      var start = 0
      var breg = 0
      var ereg = 0
      for ( i <- 0 until begin.size ) {
        if ( (begin(i) - start) >= 2 ) {
          // Should check that this document is more than just a few whitespace characters
          while ( ereg < reg.size && reg(ereg).start < begin(i) ) ereg += 1
          docs += NewDoc(id + "_" + start + "_" + begin(i),
            text.substring(start, begin(i)),
            subpage(pp, reg.slice(breg, ereg).map(_.offset(-start))),
            false)
          breg = ereg
        }
        while ( ereg < reg.size && reg(ereg).start < end(i) ) ereg += 1
        docs += NewDoc(id + "_" + begin(i) + "_" + end(i),
          text.substring(begin(i), end(i)),
          subpage(pp, reg.slice(breg, ereg).map(_.offset(-begin(i)))),
          true)
        breg = ereg
        start = end(i)
      }
      if ( (text.size - end.last) >= 2 ) {
        if ( ereg < reg.size ) ereg = reg.size
        docs += NewDoc(id + "_" + end.last + "_" + text.size,
          text.substring(end.last, text.size),
          subpage(pp, reg.slice(breg, ereg).map(_.offset(-end.last))),
          false)
      }
    }
    docs.toArray
  }
  def boilerSplit(passages: DataFrame, raw: DataFrame): DataFrame = {
    import passages.sparkSession.implicits._
    val pageField = if ( raw.columns.contains("pages") ) "pages" else "null"
    passages
      .select('id2 as "id", 'b2 as "begin", 'e2 as "end")
      .groupBy("id")
      .agg(mergeAligned(collect_list("begin"), collect_list("end")) as "spans")
      .select('id, $"spans._1" as "begin", $"spans._2" as "end")
      .join(raw, Seq("id"), "right_outer")
      .withColumn("subdoc", explode(splitDoc('id, 'text, expr(pageField), 'begin, 'end)))
      .drop("begin", "end")
      .withColumnRenamed("id", "docid")
      .withColumn("id", $"subdoc.id")
      .withColumn("text", $"subdoc.text")
      .withColumn("pages", $"subdoc.pages")
      .withColumn("aligned", $"subdoc.aligned")
      .drop("subdoc")
  }
  def clusterJoin(config: Config, clusters: DataFrame, corpus: DataFrame): DataFrame = {
    import clusters.sparkSession.implicits._
    val cols = corpus.columns.toSet
    val dateSort = if ( cols.contains("date") ) "date" else config.id

    val joint = clusters
      .join(corpus.drop("terms"), "uid")
      .withColumn("begin", 'termCharBegin('begin))
      .withColumn("end",
        'termCharEnd(when('end < size('termCharEnd), 'end)
          .otherwise(size('termCharEnd) - 1)))
      .drop("termCharBegin", "termCharEnd")
      .withColumn(config.text, getPassage(col(config.text), 'begin, 'end))
      .selectRegions("pages")
      .selectLocs("locs")

    if ( config.outputFormat == "parquet" )
      joint
    else
      joint.sort('size.desc, 'cluster, col(dateSort), col(config.id), 'begin)
  }

  def makeStringAligner(config: Config,
    matchScore: Float = 2, mismatchScore: Float = -1,
    openGap: Float = 5.0f, contGap: Float = 0.5f) = {
    val matchMatrix = jaligner.matrix.MatrixGenerator.generate(matchScore, mismatchScore)
    udf { (s1: String, s2: String) =>
      val chunks = PassFun.recursivelyAlignStrings(
        (if ( s1 != null ) s1.replaceAll("-", "\u2010") else ""),
        (if ( s2 != null ) s2.replaceAll("-", "\u2010") else ""),
        config.n, config.gap * config.gap,
        matchMatrix, openGap, contGap)
      AlignedStrings(chunks.map(_.s1).mkString, chunks.map(_.s2).mkString,
        chunks.map(_.matches).sum, chunks.map(_.score).sum)
    }
  }

  def boilerPassages(config: Config, align: DataFrame, corpus: DataFrame): DataFrame = {
    import align.sparkSession.implicits._
    val alignStrings = makeStringAligner(config, openGap = 1)
    align.drop("gid")
      .join(corpus.select('uid, col(config.id) as "id", col(config.text) as "text",
        'termCharBegin, 'termCharEnd), "uid")
      .withColumn("begin", 'termCharBegin('begin))
      .withColumn("end",
        'termCharEnd(when('end < size('termCharEnd), 'end)
          .otherwise(size('termCharEnd) - 1)))
      .drop("termCharBegin", "termCharEnd")
      .withColumn("text", getPassage('text, 'begin, 'end))
      .groupBy("mid")
      .agg(first("id") as "id1", last("id") as "id2", first("first") as "sorted",
        alignStrings(first('text) as "s1", last('text) as "s2") as "alg",
        first("begin") as "b1", last("begin") as "b2")
      .select(when('sorted, 'id1).otherwise('id2) as "id1",
        when('sorted, 'id2).otherwise('id1) as "id2",
        when('sorted, 'b1).otherwise('b2) as "b1",
        when('sorted, 'b2).otherwise('b1) as "b2",
        explode(alignedPassages(when('sorted, $"alg.s1").otherwise($"alg.s2"),
          when('sorted, $"alg.s2").otherwise($"alg.s1"))) as "pass")
      .select('id1, 'id2,
        $"pass._3" as "pairs",
        ('b1 + $"pass._1.begin") as "b1", ('b1 + $"pass._1.end") as "e1",
        ('b2 + $"pass._2.begin") as "b2", ('b2 + $"pass._2.end") as "e2")
  }

  implicit class PassageAlignments(align: DataFrame) {
    def mergePassages(relOver: Double): DataFrame = {
      import align.sparkSession.implicits._
      val graphParallelism = align.sparkSession.sparkContext.defaultParallelism

      val mergeSpans = udf { (begins: Seq[Int], ends: Seq[Int], mids: Seq[Long]) =>
        PassFun.mergeSpans(relOver, begins.zip(ends).map { x => Span(x._1, x._2) }.zip(mids))
      }

      // TODO: Bad column segmentation can interleave two texts,
      // which can lead to unrelated clusters getting merged.  One
      // possible solution would be to avoid merging passages that
      // have poor alignments.
      align.groupBy("uid", "gid")
        .agg(mergeSpans(collect_list("begin"), collect_list("end"),
          collect_list("mid")) as "spans")
        .select('uid, 'gid, explode('spans) as "span")
        .coalesce(graphParallelism)
        .select(monotonically_increasing_id() as "nid", 'uid, 'gid,
          $"span._1.begin", $"span._1.end", $"span._2" as "edges")
    }
    def pairwiseAlignments(config: Config, corpus: DataFrame): DataFrame = {
      import align.sparkSession.implicits._
      val alignStrings = makeStringAligner(config)
      val meta = corpus.drop("uid", "text", "terms", "termCharBegin", "termCharEnd",
        "regions", "pages", "locs")
      val fullalign = align.drop("gid")
        .join(corpus.select('uid, col(config.id) as "id", col(config.text) as "text",
          'termCharBegin, 'termCharEnd), "uid")
        .withColumn("bw", 'begin)
        .withColumn("ew", 'end)
        .withColumn("b", 'termCharBegin('bw))
        .withColumn("e", 'termCharEnd(when('ew < size('termCharEnd), 'ew)
          .otherwise(size('termCharEnd) - 1)))
        .select('mid, 'first, struct('uid, 'id, 'bw, 'ew, 'b, 'e,
          length('text) as "len", size('termCharBegin) as "tok",
          getPassage('text, 'b, 'e) as "text") as "info")
        .groupBy("mid")
        .agg(first("first") as "sorted", first("info") as "info1", last("info") as "info2")
        .select(when('sorted, 'info1).otherwise('info2) as "info1",
          when('sorted, 'info2).otherwise('info1) as "info2")
        .withColumn("alg", alignStrings($"info1.text", $"info2.text"))
        .select($"info1.*", $"info2.*", $"alg.*")
        .toDF("uid1", "id1", "bw1", "ew1", "b1", "e1", "len1", "tok1", "t1",
          "uid2", "id2", "bw2", "ew2", "b2", "e2", "len2", "tok2", "t2",
          "s1", "s2", "matches", "score")
        .drop("t1", "t2")
        .join(meta.toDF(meta.columns.map { _ + "1" }:_*), "id1")
        .join(meta.toDF(meta.columns.map { _ + "2" }:_*), "id2")

      val cols = fullalign.columns

      (if ( config.duppairs ) {
        fullalign.cache()
        fullalign
          .union(fullalign
            .toDF(cols.map { s =>
              if ( s endsWith "1" )
                s.replaceAll("1$", "2")
              else
                s.replaceAll("2$", "1") }:_*))
          .distinct
      } else fullalign)
        .select((cols.filter(_ endsWith "1") ++ cols.filter(_ endsWith "2") ++ Seq("matches", "score")).map(col):_*)
        .sort('id1, 'id2, 'b1, 'b2)
    }
  }

  val hashId = udf { (id: String) => hashString(id) }
  val termSpan = udf { (begin: Int, end: Int, terms: Seq[String]) =>
    terms.slice(Math.max(0, Math.min(terms.size, begin)),
      Math.max(0, Math.min(terms.size, end))).mkString(" ")
  }
  val getPassage = udf { (text: String, begin: Int, end: Int) => text.substring(begin, end) }
  def hdfsExists(spark: SparkSession, path: String) = {
    val hdfsPath = new Path(path)
    val fs = hdfsPath.getFileSystem(spark.sparkContext.hadoopConfiguration)
    val qualified = hdfsPath.makeQualified(fs.getUri, fs.getWorkingDirectory)
    fs.exists(qualified)
  }

  def main(args: Array[String]) {
    val conf = new SparkConf()
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .registerKryoClasses(Array(classOf[Coords], classOf[Region], classOf[Span], classOf[Post],
        classOf[PassAlign],
        classOf[TokText], classOf[IdSeries]))

    val spark = SparkSession
      .builder()
      .appName("PassimApplication")
      .config(conf)
      .getOrCreate()

    import spark.implicits._

    val parser = new scopt.OptionParser[Config]("passim") {
      opt[Unit]("boilerplate") action { (_, c) =>
        c.copy(boilerplate = true) } text("Detect boilerplate within groups.")
      opt[Int]('n', "n") action { (x, c) => c.copy(n = x) } validate { x =>
        if ( x > 0 ) success else failure("n-gram order must be > 0")
      } text("index n-gram features; default=5")
      opt[Int]('l', "minDF") action { (x, c) =>
        c.copy(minDF = x) } text("Lower limit on document frequency; default=2")
      opt[Int]('u', "maxDF") action { (x, c) =>
        c.copy(maxDF = x) } text("Upper limit on document frequency; default=100")
      opt[Int]('m', "min-match") action { (x, c) =>
        c.copy(minRep = x) } text("Minimum number of n-gram matches between documents; default=5")
      opt[Int]('a', "min-align") action { (x, c) =>
        c.copy(minAlg = x) } text("Minimum length of alignment; default=20")
      opt[Int]('g', "gap") action { (x, c) =>
        c.copy(gap = x) } text("Minimum size of the gap that separates passages; default=100")
      opt[Double]('o', "relative-overlap") action { (x, c) =>
        c.copy(relOver = x) } text("Minimum relative overlap to merge passages; default=0.8")
      opt[Int]('r', "max-repeat") action { (x, c) =>
        c.copy(maxRep = x) } text("Maximum repeat of one series in a cluster; default=10")
      opt[Unit]('p', "pairwise") action { (_, c) =>
        c.copy(pairwise = true) } text("Output pairwise alignments")
      opt[Unit]("duplicate-pairwise") action { (_, c) =>
        c.copy(duppairs = true) } text("Duplicate pairwise alignments")
      opt[Unit]('d', "docwise") action { (_, c) =>
        c.copy(docwise = true) } text("Output docwise alignments")
      opt[Unit]('N', "names") action { (_, c) =>
        c.copy(names = true) } text("Output names and exit")
      opt[Unit]('P', "postings") action { (_, c) =>
        c.copy(postings = true) } text("Output postings and exit")
      opt[String]('i', "id") action { (x, c) =>
        c.copy(id = x) } text("Field for unique document IDs; default=id")
      opt[String]('t', "text") action { (x, c) =>
        c.copy(text = x) } text("Field for document text; default=text")
      opt[String]('s', "group") action { (x, c) =>
        c.copy(group = x) } text("Field to group documents into series; default=series")
      opt[String]('f', "filterpairs") action { (x, c) =>
        c.copy(filterpairs = x) } text("Constraint on posting pairs; default=gid < gid2")
      opt[String]("fields") action { (x, c) =>
        c.copy(fields = x) } text("Semicolon-delimited list of fields to index")
      opt[String]("input-format") action { (x, c) =>
        c.copy(inputFormat = x) } text("Input format; default=json")
      opt[String]("output-format") action { (x, c) =>
        c.copy(outputFormat = x) } text("Output format; default=json")
      opt[Double]('w', "word-length") action { (x, c) => c.copy(wordLength = x)
      } validate { x => if ( x >= 1 ) success else failure("average word length must be >= 1")
      } text("Minimum average word length to match; default=2")
      help("help") text("prints usage text")
      arg[String]("<path>,<path>,...") action { (x, c) =>
        c.copy(inputPaths = x)
      } text("Comma-separated input paths")
      arg[String]("<path>") action { (x, c) =>
        c.copy(outputPath = x) } text("Output path")
    }

    val initConfig = parser.parse(args, Config()) match {
      case Some(c) =>
        c
      case None =>
        sys.exit(-1)
        Config()
    }

    val configFname = initConfig.outputPath + "/conf"
    val config = if ( hdfsExists(spark, configFname) ) {
      spark.read.schema(Seq(initConfig).toDF.schema).json(configFname).as[Config].first
    } else {
      spark.createDataFrame(initConfig :: Nil).coalesce(1).write.json(configFname)
      initConfig
    }

    val dfpostFname = config.outputPath + "/dfpost.parquet"
    val indexFname = config.outputPath + "/index.parquet"
    val pairsFname = config.outputPath + "/pairs.parquet"
    val passFname = config.outputPath + "/pass.parquet"
    val clusterFname = config.outputPath + "/clusters.parquet"
    val outFname = config.outputPath + "/out." + config.outputFormat

    if ( !hdfsExists(spark, outFname) ) {
      val raw = spark.read.format(config.inputFormat).load(config.inputPaths)

      val groupCol = if ( raw.columns.contains(config.group) ) config.group else config.id

      val corpus = raw.na.drop(Seq(config.id, config.text))
        .withColumn("uid", hashId(col(config.id)))
        .withColumn("gid", hashId(col(groupCol)))
        .tokenize(config.text)

      if ( config.names ) {
        corpus.select('uid, col(config.id), col(groupCol), size('terms) as "nterms")
          .write.save(config.outputPath + "/names.parquet")
        sys.exit(0)
      }

      if ( !hdfsExists(spark, clusterFname) || config.boilerplate ) {
        if ( !hdfsExists(spark, passFname) ) {
          val indexFields = ListBuffer("uid", "gid", "terms")
          if ( config.fields != "" ) indexFields ++= config.fields.split(";")
          val termCorpus = corpus.select(indexFields.toList.map(expr):_*)

          if ( !hdfsExists(spark, pairsFname) ) {
            if ( !hdfsExists(spark, dfpostFname) ) {
              val getPostings = makeIndexer(config.n, config.wordLength)

              val postings = termCorpus
                .withColumn("post", explode(getPostings('terms)))
                .drop("terms")
                .withColumn("feat", 'post("feat"))
                .withColumn("tf", 'post("tf"))
                .withColumn("post", 'post("post"))
                .filter { 'tf === 1 }
                .drop("tf")

              val df = postings.groupBy("feat").count.select('feat, 'count.cast("int") as "df")
                .filter { 'df >= config.minDF && 'df <= config.maxDF }

              postings.join(df, "feat").write.save(dfpostFname)
            }
            if ( config.postings ) sys.exit(0)

            val getPassages =
              udf { (uid: Long, uid2: Long, post: Seq[Int], post2: Seq[Int], df: Seq[Int]) =>
                val matches = PassFun.increasingMatches((post, post2, df).zipped.toSeq)
                if ( matches.size >= config.minRep ) {
                  PassFun.gappedMatches(config.n, config.gap, config.minAlg, matches)
                    .map { case ((s1, e1), (s2, e2)) =>
                      Seq(DocSpan(uid, s1, e1, true), DocSpan(uid2, s2, e2, false)) }
                } else Seq()
              }

            val dfpost = spark.read.load(dfpostFname)

            dfpost
              .join(dfpost.toDF(dfpost.columns.map { f => if ( f == "feat" ) f else f + "2" }:_*),
                "feat")
              .filter(config.filterpairs)
              .select("uid", "uid2", "post", "post2", "df")
              .groupBy("uid", "uid2")
              .agg(collect_list("post") as "post", collect_list("post2") as "post2",
                collect_list("df") as "df")
              .filter(size('post) >= config.minRep)
              .select(explode(getPassages('uid, 'uid2, 'post, 'post2, 'df)) as "pair",
                monotonically_increasing_id() as "mid") // Unique IDs serve as edge IDs in connected component graph
              .select(explode('pair) as "pass", 'mid)
              .select($"pass.*", 'mid)
              .write.parquet(pairsFname) // But we need to cache so IDs don't get reassigned.
          }

          val pairs = spark.read.parquet(pairsFname)

          val matchMatrix = jaligner.matrix.MatrixGenerator.generate(2, -1)
          val alignEdge = udf {
            (idx1: Int, idx2: Int, text1: String, text2: String, anchor: String) =>
            PassFun.alignEdge(matchMatrix, idx1, idx2, text1, text2, anchor)
          }

          val extent: Int = config.gap * 2/3
          val align = pairs
            .join(termCorpus, "uid")
            .select('mid, 'uid, 'gid, 'begin, 'end, 'first,
              termSpan('begin - extent, 'begin, 'terms) as "prefix",
              termSpan('end, 'end + extent, 'terms) as "suffix")
            .groupBy("mid")
            .agg(first("uid") as "uid", last("uid") as "uid2", first("first") as "sorted",
              first("gid") as "gid", last("gid") as "gid2",
              alignEdge(first("begin"), last("begin"),
                first("prefix"), last("prefix"), lit("R")) as "begin",
              alignEdge(first("end"), last("end"),
                first("suffix"), last("suffix"), lit("L")) as "end")
            .filter { ($"end._1" - $"begin._1") >= config.minAlg &&
              ($"end._2" - $"begin._2") >= config.minAlg }
            .select(array(struct('mid, 'uid, 'gid, 'sorted as "first",
              $"begin._1" as "begin", $"end._1" as "end"),
              struct('mid, 'uid2 as "uid", 'gid2 as "gid", !'sorted as "first",
                $"begin._2" as "begin", $"end._2" as "end")) as "pair")
            .select(explode(when('pair(0)("first"), 'pair)
              .otherwise(array('pair(1), 'pair(0)))) as "pair")
            .select($"pair.*")

          if ( config.pairwise || config.duppairs ) {
            align.pairwiseAlignments(config, corpus)
              .write.format(config.outputFormat)
              .save(config.outputPath + "/align." + config.outputFormat)
          }

          val pass = if ( config.boilerplate || config.docwise )
            boilerPassages(config, align, corpus)
          else
            align.mergePassages(config.relOver)

          if ( config.docwise ) {
            pass.write.format(config.outputFormat)
              .save(config.outputPath + "/pass." + config.outputFormat)
            sys.exit(0)
          } else if ( config.boilerplate )
            pass.drop("pairs").write.parquet(passFname)
          else
            pass.write.parquet(passFname)
        }

        if ( !config.boilerplate ) {
          val pass = spark.read.parquet(passFname).rdd

          val passNodes = pass.map {
            case Row(nid: Long, uid: Long, gid: Long, begin: Int, end: Int, edges: Seq[_]) =>
              (nid, (IdSeries(uid, gid), Span(begin, end))) }
          val passEdges = pass.flatMap {
            case Row(nid: Long, uid: Long, gid: Long, begin: Int, end: Int, edges: Seq[_]) =>
              edges.asInstanceOf[Seq[Long]].map(e => (e, nid)) }
            .groupByKey
            .map(e => {
              val nodes = e._2.toArray.sorted
              Edge(nodes(0), nodes(1), 1)
            })

          val passGraph = Graph(passNodes, passEdges)
          passGraph.cache()

          val cc = passGraph.connectedComponents()

          val clusters = passGraph.vertices.innerJoin(cc.vertices){
            (id, pass, cid) => (pass._1, (pass._2, cid.toLong))
          }
            .values
            .groupBy(_._2._2)
            .filter(x => {
              x._2.groupBy(_._1.id).values.groupBy(_.head._1.series).map(_._2.size).max <= config.maxRep
            })
            .flatMap(_._2)
            .map(x => (x._1.id, x._2))
            .groupByKey
            .flatMap(x => x._2.groupBy(_._2).values.flatMap(p => {
              PassFun.mergeSpansLR(0, p).map(z => (x._1, z._2(0), z._1.begin, z._1.end))
            }))
            .groupBy(_._2)
            .flatMap(x => {
              val size = x._2.size
              x._2.map(p => (p._1, p._2, size, p._3, p._4))
            })
            .toDF("uid", "cluster", "size", "begin", "end")

          clusters.write.parquet(clusterFname)
          passGraph.unpersist()
        }
      }

      val out = if ( config.boilerplate ) {
        boilerSplit(spark.read.parquet(passFname), raw)
      } else {
        clusterJoin(config, spark.read.parquet(clusterFname), corpus)
      }

      out.write.format(config.outputFormat).save(outFname)
    }

    spark.stop()
  }
}
