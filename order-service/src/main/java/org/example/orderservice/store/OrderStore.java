package org.example.orderservice.store;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.example.orderservice.dto.Order;
import org.springframework.stereotype.Component;

@Component
public class OrderStore {

    private final Map<Long, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    public Collection<Order> findAll() {
        return orders.values();
    }

    public Optional<Order> findById(Long id) {
        return Optional.ofNullable(orders.get(id));
    }

    public Order save(String item, int quantity) {
        Order order = new Order(idSequence.incrementAndGet(), item, quantity, "CREATED");
        orders.put(order.id(), order);
        return order;
    }

}
