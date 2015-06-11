# apim-fraud-detection-handler

This is a sample handler which publishes transaction data to WSO2 DAS for fraud detection.

For this sample, the following transaction payload template has been used.
```
{
  "id": "3a6077e0-2b82-432a-bce2-d2910d7aec74",
  "intent": "sale",
  "payer": {
    "email": "betsy@buyer.com",
    "payment_method": "credit_card",
    "funding_instruments": [
      {
        "credit_card": {
          "number": "3772822463100050",
          "type": "visa",
          "expire_month": 11,
          "expire_year": 2018,
          "cvv2": "874",
          "first_name": "Betsy",
          "last_name": "Buyer",
          "billing_address": {
            "line1": "2313 Grand Manor",
            "city": "Cleopatra",
            "state": "NY",
            "postal_code": "13961-1041",
            "country_code": "USA"
          }
        }
      }
    ]
  },
  "shipment": {
    "shipping_address": {
      "line1": "2313 Grand Manor",
      "city": "Cleopatra",
      "state": "NY",
      "postal_code": "13961-1041",
      "country_code": "USA"
    }
  },
  "transactions": [
    {
      "amount": {
        "total": "5500",
        "currency": "USD",
        "details": {
          "subtotal": "7.41",
          "tax": "0.03",
          "shipping": "0.03"
        }
      },
      "order": {
        "item_number": "I0010",
        "quantity": 1

      },
      "description": "This is the payment transaction description."
    }
  ]
}
```

This hanlder reads the connection properties for DAS from the properties file located in **APIM_HOME/repository/conf/etc/fraud-detection/fraud-detection.properties**

####A sample properties file

```
dasHost=localhost
dasPort=7611
dasUsername=admin
dasPassword=admin
streamName=transactionStream
streamVersion=1.0.0
```
