package com.khwu.lsh;

import com.datastax.spark.connector.japi.CassandraJavaUtil;
import com.khwu.cassandra.SimilarPeople;
import com.khwu.util.Utility;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.ml.feature.CountVectorizer;
import org.apache.spark.ml.feature.CountVectorizerModel;
import org.apache.spark.ml.feature.MinHashLSH;
import org.apache.spark.ml.feature.MinHashLSHModel;
import org.apache.spark.ml.linalg.Vector;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.util.Properties;

import static com.datastax.spark.connector.japi.CassandraJavaUtil.javaFunctions;
import static com.datastax.spark.connector.japi.CassandraJavaUtil.someColumns;
import static com.khwu.util.Utility.*;
import static org.apache.spark.sql.functions.callUDF;
import static org.apache.spark.sql.functions.col;

public class MeetupLSHMain {
    private static final double THRESHOLD = 0.3;
    private static final String SIMILAR_PEOPLE_TABLE = "similar_people";

    public static void main(String[] args) {
        Utility.setUpLogging();
        Properties prop;
        String master;
        if (args.length > 0) {
            prop = Utility.setUpConfig(args[0]);
            master = args[1];
        } else {
            prop = Utility.setUpConfig(Utility.DEBUG_MODE);
            master = "local[*]";
        }

        if (prop == null) return;

        SparkConf conf = new SparkConf()
                .setMaster(master)
                .setAppName("meetup-lsh")
                .set("spark.cassandra.connection.host", prop.getProperty(Utility.CASSANDRA_HOST))
                .set("spark.cassandra.connection.port", prop.getProperty(Utility.CASSANDRA_PORT));

        SparkSession spark = SparkSession
                .builder()
                .config(conf)
                .getOrCreate();

        StructType schema = Utility.setUpSchema();

        Dataset<Row> df = spark.read()
                .schema(schema)
                .json(prop.getProperty(Utility.DATA_SOURCE));

        Dataset<Row> subDF = df.select("member.member_id", "member.member_name",
                "group.group_topics.urlkey").limit(50);

        CountVectorizerModel cvModel = new CountVectorizer()
                .setInputCol("urlkey")
                .setOutputCol("feature")
                .setVocabSize(10)
                .setMinDF(2)
                .fit(subDF);

        spark.udf().register("isNoneZeroVector", (Vector v) -> v.numNonzeros() > 0, DataTypes.BooleanType);

        Dataset<Row> vectorizedDF = cvModel.transform(subDF)
                .filter(callUDF("isNoneZeroVector", col("feature")))
                .select(col("member_id"), col("member_name"),  col("feature"));

        MinHashLSH mh = new MinHashLSH().setNumHashTables(3).setInputCol("feature").setOutputCol("hashValues");

        MinHashLSHModel model = mh.fit(vectorizedDF);

        Dataset<Row> similarPPL = model.approxSimilarityJoin(vectorizedDF, vectorizedDF, THRESHOLD, "distance")
                .select(col("datasetA.member_id").alias("ida"),
                        col("datasetA.member_name").alias("namea"),
                        col("datasetB.member_id").alias("idb"),
                        col("datasetB.member_name").alias("nameb"),
                        col("distance"));

        JavaRDD<SimilarPeople> rdd = similarPPL
                .toJavaRDD()
                .map(row -> {
                    SimilarPeople s = new SimilarPeople();
                    s.setIda(row.getInt(0));
                    s.setNamea(row.getString(1));
                    s.setIdb(row.getInt(2));
                    s.setNameb(row.getString(3));
                    s.setDistance(row.getDouble(4));
                    return s;
                });

        CassandraJavaUtil.javaFunctions(rdd)
                .writerBuilder(CASSANDRA_KEYSPACE, SIMILAR_PEOPLE_TABLE, CassandraJavaUtil.mapToRow(SimilarPeople.class))
                .withColumnSelector(someColumns("ida", "namea", "idb", "nameb", "distance"))
                .saveToCassandra();

        spark.stop();
    }
}