package org.example.orderservice.dto;

public record Order(Long id, String item, int quantity, String status) {
}
