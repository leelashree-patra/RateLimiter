package org.example.orderservice.entity;

public record Order(Long id, String item, int quantity, String status) {
}
