# GENERATED from config/data-contract.yml by ./gradlew :bromo-app:generateWarehouseSql. Do not edit.
BRONZE='bronze'
SILVER='silver'
GOLD='gold'
BRONZE_TABLE='bronze.events'
SILVER_TABLE='silver.events'
SILVER_REJECTED_TABLE='silver.events_rejected'
GOLD_TABLES=('gold.events_1m')
SAMPLE_TYPE='com.example.payment.v1'
SAMPLE_SOURCE='sample-source'
SAMPLE_SUBJECT='card-1'
SAMPLE_DATA='{"amount":42.5,"label":"ok","merchant":"m1"}'
SAMPLE_FIELD_CHECKS=('amount|42.5' 'label|ok')
SAMPLE_ATTR_CHECKS=('merchant|m1')
