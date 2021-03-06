package com.mycompany.mia.recsys

import java.io.File
import java.io.StringReader

import scala.Array.canBuildFrom
import scala.collection.JavaConversions.asScalaIterator
import scala.collection.JavaConversions.iterableAsScalaIterable
import scala.collection.mutable.ArrayBuffer
import scala.io.Source

import org.apache.lucene.analysis.core.WhitespaceAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.Field.Index
import org.apache.lucene.document.Field.Store
import org.apache.lucene.document.Field.TermVector
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queries.mlt.MoreLikeThis
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.SimpleFSDirectory
import org.apache.lucene.util.Version
import org.apache.mahout.cf.taste.impl.model.file.FileDataModel

/**
 * TopN Item Item Collaborative Filtering Recommender that
 * uses Lucene as its source for Item-Item Similarity.
 */
class LuceneIICFRecommender(
    modelfile: File, tagfile: File, indexdir: File) {

  val model = new FileDataModel(modelfile)

  val analyzer = new WhitespaceAnalyzer(Version.LUCENE_43)
  val indexReader = openIndex(tagfile, indexdir)
  val indexSearcher = new IndexSearcher(indexReader)

  /**
   * Given a user, return the topN items that the user
   * may be interested in. Do not include items user has
   * rated already.
   * @param user the userID
   * @param topN the number of items to recommend.
   * @return a List of Pairs of itemID and similarity score.
   */
  def recommend(user: Long, topN: Int): List[(Long,Double)] = {
    model.getItemIDs()
      .map(item => (item.toLong, predict(user, item))) 
      .filter(p => p._2 > 0.0D)
      .toList
      .sortWith((a,b) => a._2 > b._2)
      .slice(0, topN)
  }

  /**
   * Predict the rating that a user would give an item.
   * If the user has already rated the item, we return
   * the actual rating.
   * @param user the user ID.
   * @param item the item ID.
   * @return the predicted rating the user would rate 
   *         the item.
   */
  def predict(user: Long, item: Long): Double = {
    val ratedItems = getRatedItems(user)
    if (ratedItems.contains(item)) 
      model.getPreferenceValue(user, item).toDouble
    else {
      val nds = ratedItems.map(j => {
        val simIJ = similarity(item, j, 20)
        val rUJ = model.getPreferenceValue(user, j)
        (simIJ * rUJ, simIJ)
      })
      val numer = nds.map(_._1).foldLeft(0.0D)(_ + _)
      val denom = nds.map(_._2).foldLeft(0.0D)(_ + _)
      numer / denom
    }
  }

  /**
   * Return a set of items that have been rated by
   * this user.
   * @param user the user ID.
   * @return Set of items not yet rated by this user.
   */
  def getRatedItems(user: Long): Set[Long] = {
    model.getPreferencesFromUser(user)
      .map(pref => pref.getItemID())
      .toSet
  }

  /**
   * Returns the similarity between two items, limited
   * to the specified neighborhood size. If item is too
   * dissimilar (ie out of the specified item neighborhood
   * size) then the similarity returned is 0.0.
   * @param itemI the item ID for the i-th item.
   * @param itemJ the item ID for the j-th item.
   * @param nnbrs item neighborhood size
   * @return similarity between itemI and itemJ.
   */
  def similarity(itemI: Long, itemJ: Long, nnbrs: Int): Double = {
    val simItemScores = similarItems(itemI, nnbrs)
      .filter(itemScore => itemScore._2 > 0.0D)
      .toMap
    simItemScores.getOrElse(itemJ, 0.0D)
  }
  
  /**
   * Find a neighborhood of items of size nnbrs which are most
   * similar to the item specified. Uses Lucene MoreLikeThis
   * query to calculate the similarity.
   * @param item the source item.
   * @param nnbrs the neighborhood size.
   * @return a List of (item ID, similarity) tuples representing
   *         the item neighborhood.
   */
  def similarItems(item: Long, nnbrs: Int): List[(Long,Double)] = {
    // find docID for source document
	val hits = indexSearcher.search(
	  new TermQuery(new Term("itemID", item.toString)), 1)
    if (hits.totalHits == 0) List()
    else {
      val docID = hits.scoreDocs(0).doc
      val mlt = new MoreLikeThis(indexReader)
      mlt.setMinTermFreq(0)
      mlt.setMinDocFreq(0)
      mlt.setFieldNames(Array[String]("tags"))
      mlt.setAnalyzer(analyzer)
      val doc = indexReader.document(docID)
      val tags = doc.getValues("tags").mkString(" ")
      val mltq = mlt.like(new StringReader(tags), null)
      val rhits = indexSearcher.search(mltq, nnbrs + 1).scoreDocs
      rhits.map(rhit => {
        val rdoc = indexReader.document(rhit.doc)
        (rdoc.get("itemID").toLong, rhit.score.toDouble)
      })
      .toList
      .filter(docsim => docsim._1 != item)
    }
  }

  /**
   * Create a Lucene index from the movie tags file if it does
   * not exist already, then return a handle to the IndexReader.
   * @param tagfile the File representing the movie-tags.csv
   * @param indexdir the Lucene index directory.
   * @return the reference to the IndexReader.
   */
  def openIndex(tagfile: File, indexdir: File): IndexReader = {
    if (! indexdir.exists()) {
      // build index from data
      indexdir.mkdirs();
      val iwconf = new IndexWriterConfig(Version.LUCENE_43, 
        analyzer)
      iwconf.setOpenMode(IndexWriterConfig.OpenMode.CREATE)
      val indexWriter = new IndexWriter(
        new SimpleFSDirectory(indexdir), iwconf)
      var prevItemID = -1L
      var tagBuf = ArrayBuffer[String]()
      Source.fromFile(tagfile)
        .getLines()
        .foreach(line => {
           val Array(itemID, tag) = line.split(",")
           if (itemID.toInt == prevItemID || prevItemID < 0L) {
             tagBuf += tag.replaceAll(" ", "_").toLowerCase()
           } else {
             val doc = new Document()
             doc.add(new Field("itemID", itemID, 
               Store.YES, Index.NOT_ANALYZED))
             doc.add(new Field("tags", tagBuf.mkString(" "), 
               Store.YES, Index.ANALYZED, TermVector.YES))
             indexWriter.addDocument(doc)
             tagBuf.clear
             tagBuf += tag.replaceAll(" ", "_").toLowerCase()
           }
           prevItemID = itemID.toInt
        })
      indexWriter.commit()
      indexWriter.close()
    }
    DirectoryReader.open(new SimpleFSDirectory(indexdir))
  }
}