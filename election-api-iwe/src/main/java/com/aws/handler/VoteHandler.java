package com.aws.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Random;

public class VoteHandler implements RequestHandler<KinesisEvent, Void> {

    private final String TABLE_NAME = System.getenv("TABLE_NAME");
    private static DynamoDbClient client = null;

    {
        if (client == null) {
            client = DynamoDbClient.builder()
                    .httpClient(UrlConnectionHttpClient.builder().build())
                    .build();
        }

        final HashMap<String, AttributeValue> keyToGet = new HashMap<String, AttributeValue>();
        keyToGet.put("candidate", AttributeValue.builder()
                .s("Invalid").build());

        final GetItemRequest request = GetItemRequest.builder()
                .key(keyToGet)
                .tableName(TABLE_NAME)
                .build();

        client.getItem(request).item();
    }

    @Override
    public Void handleRequest(final KinesisEvent request, final Context context) {

        for(KinesisEvent.KinesisEventRecord rec : request.getRecords()) {
            String candidateIdentifier = new String(rec.getKinesis().getData().array());
            context.getLogger().log("[#] - Payload:" + candidateIdentifier + " | PK: " + rec.getKinesis().getPartitionKey());
            this.vote(candidateIdentifier);
        }
        return null;
    }

    private void vote(final String candidateIdentifier) {

        final int shardNumber = new Random().nextInt(2);
        final String partitionValue = "Candidate" + candidateIdentifier + "#" + shardNumber;

        final HashMap<String, AttributeValue> keyValues = new HashMap<String, AttributeValue>();
        keyValues.put("candidate", AttributeValue.builder().s(partitionValue).build());

        final HashMap<String, AttributeValue> expressionValues = new HashMap<String, AttributeValue>();
        expressionValues.put(":val", AttributeValue.builder().n("1").build());
        expressionValues.put(":val1", AttributeValue.builder().s(ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_ZONED_DATE_TIME)).build());

        final UpdateItemRequest updateItemRequest = UpdateItemRequest.builder()
                .key(keyValues)
                .tableName(TABLE_NAME)
                .updateExpression("set voteCounter = voteCounter + :val, lastUpdate = :val1")
                .expressionAttributeValues(expressionValues).build();

        try {
            client.updateItem(updateItemRequest);
        } catch (DynamoDbException e) {

            final HashMap<String, AttributeValue> itemValues = new HashMap<String, AttributeValue>();
            itemValues.put("candidate", AttributeValue.builder().s(partitionValue).build());
            itemValues.put("voteCounter", AttributeValue.builder().n("1").build());
            itemValues.put("lastUpdate", AttributeValue.builder().s(ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_ZONED_DATE_TIME)).build());

            final PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(itemValues)
                    .build();

            client.putItem(putItemRequest);
        }
    }
}
