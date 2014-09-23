package ignition.core.jobs.utils

import java.util.Date

import org.apache.hadoop.io.LongWritable
import org.apache.spark.SparkContext
import org.apache.hadoop.fs.{FileStatus, Path, FileSystem}
import org.apache.spark.rdd.RDD
import org.joda.time.{DateTimeZone, DateTime}

import scala.reflect.ClassTag
import scala.util.Try


object SparkContextUtils {

  implicit class SparkContextImprovements(sc: SparkContext) {

    private def getFileSystem(path: Path): FileSystem = {
      path.getFileSystem(sc.hadoopConfiguration)
    }

    private def getStatus(commaSeparatedPaths: String, removeEmpty: Boolean): Seq[FileStatus] = {
      val paths = ignition.core.utils.HadoopUtils.getPathStrings(commaSeparatedPaths).map(new Path(_)).toSeq
      val fs = getFileSystem(paths.head)
      for {
        path <- paths
        status <- Option(fs.globStatus(path)).getOrElse(Array.empty).toSeq
        if status.isDirectory || !removeEmpty || status.getLen > 0 // remove empty files if necessary
      } yield status
    }

    private def delete(path: Path): Unit = {
      val fs = getFileSystem(path)
      fs.delete(path, true)
    }

    // This call is equivalent to a ls -d in shell, but won't fail if part of a path matches nothing,
    // For instance, given path = s3n://bucket/{a,b}, it will work fine if a exists but b is missing
    def sortedGlobPath(path: String, removeEmpty: Boolean = true): Seq[String] = {
      val paths = ignition.core.utils.HadoopUtils.getPathStrings(path)
      paths.flatMap(p => getStatus(p, removeEmpty)).map(_.getPath.toString).distinct.sorted
    }

    // This method's purpose is to skip empty files on a given path (to work around the fact that empty files gives errors to hadoop)
    // if the path expands only to files, it will just filter out the empty ones
    // if it expands to a directory, then it will get all non empty files from this directory (but will ignore subdirectories)
    def nonEmptyFilesPath(path: String, failOnEmpty: Boolean = true): Set[String] = {
      // getStatus only get non empty files
      val status = getStatus(path, removeEmpty = true)
      val (dirs, files) = status.partition(f => f.isDirectory)
      val filesFromDirs = dirs.flatMap(dir => getStatus(dir.getPath.toString + "/*", removeEmpty = true)).filter(p => p.isFile)
      val finalFilesStatus = files.filter(_.isFile) ++ filesFromDirs
      val finalFiles = finalFilesStatus.map(_.getPath.toString).toSet

      if (failOnEmpty && finalFiles.isEmpty)
        throw new Exception(s"Zero non-empty files matched by: $path")

      finalFiles
    }

    // This function will expand the paths then group they and give to RDDs
    // We group to avoid too many RDDs on union (each RDD take some memory on driver)
    // We avoid passing a path too big to one RDD to avoid a Hadoop bug where just part of the path is processed when the path is big
    private def processPaths(f: (String) => RDD[String], initialPaths: Seq[String], minimumPaths: Int): RDD[String] = {
      val processedPaths = for {
        p <- initialPaths
        nonEmptyPath <- nonEmptyFilesPath(p, failOnEmpty = false)
      } yield nonEmptyPath

      if (processedPaths.size < minimumPaths)
        throw new Exception(s"Not enough paths found for $initialPaths")

      val rdds = processedPaths.grouped(50).map(pathGroup => f(pathGroup.mkString(",")))

      rdds.fold(sc.parallelize(Seq.empty))(_ union _)
    }

    private def nonEmptyTextFile(paths: Seq[String], minimumPaths: Int): RDD[String] = {
      processPaths((p) => sc.textFile(p), paths, minimumPaths)
    }

    private def filterPaths(path: String,
                            requireSuccess: Boolean = false,
                            startDate: Option[DateTime],
                            endDate: Option[DateTime],
                            lastN: Option[Int],
                            ignoreMalformedDates: Boolean): Seq[String] = {
      val sortedPaths = sortedGlobPath(path)
      val filteredByDate = if (startDate.isEmpty && endDate.isEmpty)
        sortedPaths
      else
        sortedPaths.filter(p => {
          val tryDate = Try { PathUtils.extractDate(p) }
          if (tryDate.isFailure && ignoreMalformedDates)
            false
          else {
            val date = tryDate.get
            val goodStartDate = startDate.isEmpty || date.withZone(DateTimeZone.UTC).equals(startDate.get.withZone(DateTimeZone.UTC)) || date.isAfter(startDate.get)
            val goodEndDate = endDate.isEmpty || date.withZone(DateTimeZone.UTC).equals(endDate.get.withZone(DateTimeZone.UTC)) || date.isBefore(endDate.get)
            goodStartDate && goodEndDate
          }
        })

      // Use a stream here to avoid checking the success if we are going to just take a few files
      val filteredBySuccessAndReversed = filteredByDate.reverse.toStream.dropWhile(p => requireSuccess && sortedGlobPath(s"$p/{_SUCCESS,_FINISHED}", removeEmpty = false).isEmpty)

      if (lastN.isDefined)
        filteredBySuccessAndReversed.take(lastN.get).reverse.toList
      else
        filteredBySuccessAndReversed.reverse.toList
    }

    def getFilteredPaths(path: String,
                         requireSuccess: Boolean,
                         startDate: Option[DateTime],
                         endDate: Option[DateTime],
                         lastN: Option[Int],
                         ignoreMalformedDates: Boolean): Seq[String] = {
      require(lastN.isEmpty || endDate.isDefined, "If you are going to get the last files, better specify the end date to avoid getting files in the future")
      filterPaths(path, requireSuccess, startDate, endDate, lastN, ignoreMalformedDates)
    }


    lazy val hdfsPathPrefix = sc.master.replaceFirst("spark://(.*):7077", "hdfs://$1:9000/")

    def synchToHdfs(paths: Seq[String], forceSynch: Boolean): Seq[String] = {
      def mapPaths(actionWhenNeedsSynching: (String, String) => Unit): Seq[String] = {
        paths.map(p => {
          val hdfsPath = p.replace("s3n://", hdfsPathPrefix)
          if (forceSynch || getStatus(hdfsPath, false).isEmpty || getStatus(s"$hdfsPath/*", true).filter(_.isFile).size != sc.defaultParallelism) {
            val _hdfsPath = new Path(hdfsPath)
            actionWhenNeedsSynching(p, hdfsPath)
          }
          hdfsPath
        })
      }
      // We delete first because we may have two paths in the same parent
      mapPaths((p, hdfsPath) => delete(new Path(hdfsPath).getParent))// delete parent to avoid old files being accumulated
      mapPaths((p, hdfsPath) => nonEmptyTextFile(Seq(p), 0).coalesce(sc.defaultParallelism, true).saveAsTextFile(hdfsPath))
    }


    def prepareRDDForMultipleReads[V: ClassTag](rdd: RDD[V], deleteOld: Boolean = true): RDD[V] = {
      val path = s"${hdfsPathPrefix}rddsForMultipleReads/${new Date().getTime()}"
      if (deleteOld)
        delete(new Path(path).getParent)
      rdd.saveAsObjectFile(path)
      sc.objectFile[V](path)
    }

    def filterAndGetTextFiles(path: String,
                              requireSuccess: Boolean = false,
                              startDate: Option[DateTime] = None,
                              endDate: Option[DateTime] = None,
                              lastN: Option[Int] = None,
                              synchLocally: Boolean = false,
                              forceSynch: Boolean = false,
                              ignoreMalformedDates: Boolean = false,
                              minimumPaths: Int = 1): RDD[String] = {
      val paths = getFilteredPaths(path, requireSuccess, startDate, endDate, lastN, ignoreMalformedDates)
      if (paths.size < minimumPaths)
        throw new Exception(s"Tried with start/end time equals to $startDate/$endDate for path $path but but the resulting number of paths $paths is less than the required")
      else if (synchLocally)
        nonEmptyTextFile(synchToHdfs(paths, forceSynch), minimumPaths)
      else
        nonEmptyTextFile(paths, minimumPaths)
    }

    private def stringHadoopFile(paths: Seq[String], minimumPaths: Int): RDD[String] = {
      processPaths((p) => sc.sequenceFile(p, classOf[LongWritable], classOf[org.apache.hadoop.io.BytesWritable])
                .map({ case (k, v) => new String(v.getBytes) }), paths, minimumPaths)
    }

    def filterAndGetStringHadoopFiles(path: String, requireSuccess: Boolean = false,
                                      startDate: Option[DateTime] = None,
                                      endDate: Option[DateTime] = None,
                                      lastN: Option[Int] = None,
                                      ignoreMalformedDates: Boolean = false,
                                      minimumPaths: Int = 1): RDD[String] = {
      val paths = getFilteredPaths(path, requireSuccess, startDate, endDate, lastN, ignoreMalformedDates)
      if (paths.size < minimumPaths)
        throw new Exception(s"Tried with start/end time equals to $startDate/$endDate for path $path but but the resulting number of paths $paths is less than the required")
      else
        stringHadoopFile(paths, minimumPaths)
    }
  }
}
