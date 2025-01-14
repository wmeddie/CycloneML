/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.jdbc.v2

import java.util

import org.apache.log4j.Level

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.{IndexAlreadyExistsException, NoSuchIndexException}
import org.apache.spark.sql.connector.catalog.{Catalogs, Identifier, TableCatalog}
import org.apache.spark.sql.connector.catalog.index.SupportsIndex
import org.apache.spark.sql.connector.expressions.{FieldReference, NamedReference}
import org.apache.spark.sql.jdbc.DockerIntegrationFunSuite
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._
import org.apache.spark.tags.DockerTest

@DockerTest
private[v2] trait V2JDBCTest extends SharedSparkSession with DockerIntegrationFunSuite {
  val catalogName: String
  // dialect specific update column type test
  def testUpdateColumnType(tbl: String): Unit

  def notSupportsTableComment: Boolean = false

  val defaultMetadata = new MetadataBuilder().putLong("scale", 0).build()

  def testUpdateColumnNullability(tbl: String): Unit = {
    sql(s"CREATE TABLE $catalogName.alt_table (ID STRING NOT NULL)")
    var t = spark.table(s"$catalogName.alt_table")
    // nullable is true in the expectedSchema because Spark always sets nullable to true
    // regardless of the JDBC metadata https://github.com/apache/spark/pull/18445
    var expectedSchema = new StructType().add("ID", StringType, true, defaultMetadata)
    assert(t.schema === expectedSchema)
    sql(s"ALTER TABLE $catalogName.alt_table ALTER COLUMN ID DROP NOT NULL")
    t = spark.table(s"$catalogName.alt_table")
    expectedSchema = new StructType().add("ID", StringType, true, defaultMetadata)
    assert(t.schema === expectedSchema)
    // Update nullability of not existing column
    val msg = intercept[AnalysisException] {
      sql(s"ALTER TABLE $catalogName.alt_table ALTER COLUMN bad_column DROP NOT NULL")
    }.getMessage
    assert(msg.contains("Missing field bad_column"))
  }

  def testRenameColumn(tbl: String): Unit = {
    sql(s"ALTER TABLE $tbl RENAME COLUMN ID TO RENAMED")
    val t = spark.table(s"$tbl")
    val expectedSchema = new StructType().add("RENAMED", StringType, true, defaultMetadata)
      .add("ID1", StringType, true, defaultMetadata).add("ID2", StringType, true, defaultMetadata)
    assert(t.schema === expectedSchema)
  }

  def testCreateTableWithProperty(tbl: String): Unit = {}

  test("SPARK-33034: ALTER TABLE ... add new columns") {
    withTable(s"$catalogName.alt_table") {
      sql(s"CREATE TABLE $catalogName.alt_table (ID STRING)")
      var t = spark.table(s"$catalogName.alt_table")
      var expectedSchema = new StructType().add("ID", StringType, true, defaultMetadata)
      assert(t.schema === expectedSchema)
      sql(s"ALTER TABLE $catalogName.alt_table ADD COLUMNS (C1 STRING, C2 STRING)")
      t = spark.table(s"$catalogName.alt_table")
      expectedSchema = expectedSchema.add("C1", StringType, true, defaultMetadata)
        .add("C2", StringType, true, defaultMetadata)
      assert(t.schema === expectedSchema)
      sql(s"ALTER TABLE $catalogName.alt_table ADD COLUMNS (C3 STRING)")
      t = spark.table(s"$catalogName.alt_table")
      expectedSchema = expectedSchema.add("C3", StringType, true, defaultMetadata)
      assert(t.schema === expectedSchema)
      // Add already existing column
      val msg = intercept[AnalysisException] {
        sql(s"ALTER TABLE $catalogName.alt_table ADD COLUMNS (C3 DOUBLE)")
      }.getMessage
      assert(msg.contains("Cannot add column, because C3 already exists"))
    }
    // Add a column to not existing table
    val msg = intercept[AnalysisException] {
      sql(s"ALTER TABLE $catalogName.not_existing_table ADD COLUMNS (C4 STRING)")
    }.getMessage
    assert(msg.contains("Table not found"))
  }

  test("SPARK-33034: ALTER TABLE ... drop column") {
    withTable(s"$catalogName.alt_table") {
      sql(s"CREATE TABLE $catalogName.alt_table (C1 INTEGER, C2 STRING, c3 INTEGER)")
      sql(s"ALTER TABLE $catalogName.alt_table DROP COLUMN C1")
      sql(s"ALTER TABLE $catalogName.alt_table DROP COLUMN c3")
      val t = spark.table(s"$catalogName.alt_table")
      val expectedSchema = new StructType().add("C2", StringType, true, defaultMetadata)
      assert(t.schema === expectedSchema)
      // Drop not existing column
      val msg = intercept[AnalysisException] {
        sql(s"ALTER TABLE $catalogName.alt_table DROP COLUMN bad_column")
      }.getMessage
      assert(msg.contains(s"Missing field bad_column in table $catalogName.alt_table"))
    }
    // Drop a column from a not existing table
    val msg = intercept[AnalysisException] {
      sql(s"ALTER TABLE $catalogName.not_existing_table DROP COLUMN C1")
    }.getMessage
    assert(msg.contains("Table not found"))
  }

  test("SPARK-33034: ALTER TABLE ... update column type") {
    withTable(s"$catalogName.alt_table") {
      testUpdateColumnType(s"$catalogName.alt_table")
      // Update not existing column
      val msg2 = intercept[AnalysisException] {
        sql(s"ALTER TABLE $catalogName.alt_table ALTER COLUMN bad_column TYPE DOUBLE")
      }.getMessage
      assert(msg2.contains("Missing field bad_column"))
    }
    // Update column type in not existing table
    val msg = intercept[AnalysisException] {
      sql(s"ALTER TABLE $catalogName.not_existing_table ALTER COLUMN id TYPE DOUBLE")
    }.getMessage
    assert(msg.contains("Table not found"))
  }

  test("SPARK-33034: ALTER TABLE ... rename column") {
    withTable(s"$catalogName.alt_table") {
      sql(s"CREATE TABLE $catalogName.alt_table (ID STRING NOT NULL," +
        s" ID1 STRING NOT NULL, ID2 STRING NOT NULL)")
      testRenameColumn(s"$catalogName.alt_table")
      // Rename to already existing column
      val msg = intercept[AnalysisException] {
        sql(s"ALTER TABLE $catalogName.alt_table RENAME COLUMN ID1 TO ID2")
      }.getMessage
      assert(msg.contains("Cannot rename column, because ID2 already exists"))
    }
    // Rename a column in a not existing table
    val msg = intercept[AnalysisException] {
      sql(s"ALTER TABLE $catalogName.not_existing_table RENAME COLUMN ID TO C")
    }.getMessage
    assert(msg.contains("Table not found"))
  }

  test("SPARK-33034: ALTER TABLE ... update column nullability") {
    withTable(s"$catalogName.alt_table") {
      testUpdateColumnNullability(s"$catalogName.alt_table")
    }
    // Update column nullability in not existing table
    val msg = intercept[AnalysisException] {
      sql(s"ALTER TABLE $catalogName.not_existing_table ALTER COLUMN ID DROP NOT NULL")
    }.getMessage
    assert(msg.contains("Table not found"))
  }

  test("CREATE TABLE with table comment") {
    withTable(s"$catalogName.new_table") {
      val logAppender = new LogAppender("table comment")
      withLogAppender(logAppender) {
        sql(s"CREATE TABLE $catalogName.new_table(i INT) COMMENT 'this is a comment'")
      }
      val createCommentWarning = logAppender.loggingEvents
        .filter(_.getLevel == Level.WARN)
        .map(_.getRenderedMessage)
        .exists(_.contains("Cannot create JDBC table comment"))
      assert(createCommentWarning === notSupportsTableComment)
    }
  }

  test("CREATE TABLE with table property") {
    withTable(s"$catalogName.new_table") {
      val m = intercept[AnalysisException] {
        sql(s"CREATE TABLE $catalogName.new_table (i INT) TBLPROPERTIES('a'='1')")
      }.message
      assert(m.contains("Failed table creation"))
      testCreateTableWithProperty(s"$catalogName.new_table")
    }
  }

  def supportsIndex: Boolean = false
  def testIndexProperties(jdbcTable: SupportsIndex): Unit = {}
  def testIndexUsingSQL(tbl: String): Unit = {}

  test("SPARK-36913: Test INDEX") {
    if (supportsIndex) {
      withTable(s"$catalogName.new_table") {
        sql(s"CREATE TABLE $catalogName.new_table(col1 INT, col2 INT, col3 INT," +
          s" col4 INT, col5 INT)")
        val loaded = Catalogs.load(catalogName, conf)
        val jdbcTable = loaded.asInstanceOf[TableCatalog]
          .loadTable(Identifier.of(Array.empty[String], "new_table"))
          .asInstanceOf[SupportsIndex]
        assert(jdbcTable.indexExists("i1") == false)
        assert(jdbcTable.indexExists("i2") == false)

        val properties = new util.HashMap[String, String]();
        val indexType = "DUMMY"
        var m = intercept[UnsupportedOperationException] {
          jdbcTable.createIndex("i1", indexType, Array(FieldReference("col1")),
            new util.HashMap[NamedReference, util.Map[String, String]](), properties)
        }.getMessage
        assert(m.contains(s"Index Type $indexType is not supported." +
          s" The supported Index Types are: BTREE and HASH"))

        jdbcTable.createIndex("i1", "BTREE", Array(FieldReference("col1")),
          new util.HashMap[NamedReference, util.Map[String, String]](), properties)

        jdbcTable.createIndex("i2", "",
          Array(FieldReference("col2"), FieldReference("col3"), FieldReference("col5")),
          new util.HashMap[NamedReference, util.Map[String, String]](), properties)

        assert(jdbcTable.indexExists("i1") == true)
        assert(jdbcTable.indexExists("i2") == true)

        m = intercept[IndexAlreadyExistsException] {
          jdbcTable.createIndex("i1", "", Array(FieldReference("col1")),
            new util.HashMap[NamedReference, util.Map[String, String]](), properties)
        }.getMessage
        assert(m.contains("Failed to create index: i1 in new_table"))

        var index = jdbcTable.listIndexes()
        assert(index.length == 2)

        assert(index(0).indexName.equals("i1"))
        assert(index(0).indexType.equals("BTREE"))
        var cols = index(0).columns
        assert(cols.length == 1)
        assert(cols(0).describe().equals("col1"))
        assert(index(0).properties.size == 0)

        assert(index(1).indexName.equals("i2"))
        assert(index(1).indexType.equals("BTREE"))
        cols = index(1).columns
        assert(cols.length == 3)
        assert(cols(0).describe().equals("col2"))
        assert(cols(1).describe().equals("col3"))
        assert(cols(2).describe().equals("col5"))
        assert(index(1).properties.size == 0)

        jdbcTable.dropIndex("i1")
        assert(jdbcTable.indexExists("i1") == false)
        assert(jdbcTable.indexExists("i2") == true)

        index = jdbcTable.listIndexes()
        assert(index.length == 1)

        assert(index(0).indexName.equals("i2"))
        assert(index(0).indexType.equals("BTREE"))
        cols = index(0).columns
        assert(cols.length == 3)
        assert(cols(0).describe().equals("col2"))
        assert(cols(1).describe().equals("col3"))
        assert(cols(2).describe().equals("col5"))

        jdbcTable.dropIndex("i2")
        assert(jdbcTable.indexExists("i1") == false)
        assert(jdbcTable.indexExists("i2") == false)
        index = jdbcTable.listIndexes()
        assert(index.length == 0)

        m = intercept[NoSuchIndexException] {
          jdbcTable.dropIndex("i2")
        }.getMessage
        assert(m.contains("Failed to drop index: i2"))

        testIndexProperties(jdbcTable)
      }
    }
  }

  test("SPARK-36895: Test INDEX Using SQL") {
    withTable(s"$catalogName.new_table") {
      sql(s"CREATE TABLE $catalogName.new_table(col1 INT, col2 INT, col3 INT, col4 INT, col5 INT)")
      testIndexUsingSQL(s"$catalogName.new_table")
    }
  }
}
