(ns metabase.driver.redshift-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [metabase.driver.sql-jdbc.execute :as execute]
            [metabase.plugins.jdbc-proxy :as jdbc-proxy]
            [metabase.query-processor :as qp]
            [metabase.test :as mt]
            [metabase.test.data.datasets :refer [expect-with-driver]]
            [metabase.test.data.redshift :as rstest]
            [metabase.test.fixtures :as fixtures]
            [metabase.test.util :as tu]
            [metabase.util :as u]))

(use-fixtures :once (fixtures/initialize :plugins))

(expect-with-driver :redshift
  "UTC"
  (tu/db-timezone-id))

(deftest correct-driver-test
  (is (= "com.amazon.redshift.jdbc.Driver"
         (.getName (class (jdbc-proxy/wrapped-driver (java.sql.DriverManager/getDriver "jdbc:redshift://host:5432/testdb")))))
      "Make sure we're using the correct driver for Redshift"))

(defn- query->native [query]
  (let [native-query (atom nil)]
    (with-redefs [execute/prepared-statement (fn [_ _ sql _]
                                               (reset! native-query sql)
                                               (throw (Exception. "done")))]
      (u/ignore-exceptions
       (qp/process-query query))
      @native-query)))

;; TODO: Add executed-by and card-id and such to this
(deftest remark-test
  (let [expected (str/replace
                  (str
                   "-- /* partner: \"metabase\", {\"dashboard_id\":null,\"chart_id\":null,\"optional_user_id\":1000,\"optional_account_id\":null,"
                   "\"filter_values\":{\"userid\":1,\"firstname\":\"Rafael\"}} */"
                   " Metabase:: userID: 1000 queryType: MBQL queryHash: cb83d4f6eedc250edb0f2c16f8d9a21e5d42f322ccece1494c8ef3d634581fe2\n"
                   "SELECT \"%schema%\".\"test_data_users\".\"id\" AS \"id\","
                   " \"%schema%\".\"test_data_users\".\"name\" AS \"name\","
                   " \"%schema%\".\"test_data_users\".\"last_login\" AS \"last_login\""
                   " FROM \"%schema%\".\"test_data_users\""
                   " WHERE (\"%schema%\".\"test_data_users\".\"id\" = 1 AND \"%schema%\".\"test_data_users\".\"name\" = ?)"
                   " LIMIT 2000")
                  "%schema%" rstest/session-schema-name)]
   (mt/test-driver
    :redshift
    (is (= expected
           (query->native
            {:database (mt/id)
             :query
             {:source-table (mt/id :users)
              :filter
              [:and
               [:= [:field-id (mt/id :users :id)] [:value 1 {:name "userid"}]]
               [:= [:field-id (mt/id :users :name)] [:value "Rafael" {:name "firstname"}]]]
              :limit 2000}
             :type :query
             :info {:executed-by 1000
                    :context :ad-hoc
                    :nested? false
                    :query-hash (byte-array [-53, -125, -44, -10, -18, -36, 37, 14, -37, 15, 44, 22, -8, -39, -94, 30, 93, 66, -13, 34, -52, -20, -31, 73, 76, -114, -13, -42, 52, 88, 31, -30])}}))
        "if I run a Redshift query, does it get a remark added to it?"))))
