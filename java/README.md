# photon-java
Java Photon Wrapper for DynamoDB

```java
try (PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
        .endpoint("cache.example.com:6379")
        .authProvider(iamAuthProvider)
        .origin(DynamoDbClient.builder()
                .region(Region.US_EAST_1))
        .build()) {
    DynamoDbClient dynamo = result.client();
}
```
