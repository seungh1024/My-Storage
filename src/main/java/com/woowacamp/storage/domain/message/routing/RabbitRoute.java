package com.woowacamp.storage.domain.message.routing;

public record RabbitRoute(String exchange, String routingKey) {
}
