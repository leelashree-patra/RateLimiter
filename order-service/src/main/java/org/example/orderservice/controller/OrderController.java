package org.example.orderservice.controller;

import java.util.Collection;

import org.example.orderservice.dto.Order;
import org.example.orderservice.store.OrderStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderStore orderStore;

    public OrderController(OrderStore orderStore) {
        this.orderStore = orderStore;
    }

    @GetMapping
    public Collection<Order> findAll() {
        return orderStore.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> findById(@PathVariable Long id) {
        return orderStore.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Order> create(@RequestBody CreateOrderRequest request) {
        Order order = orderStore.save(request.item(), request.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    public record CreateOrderRequest(String item, int quantity) {
    }

}
