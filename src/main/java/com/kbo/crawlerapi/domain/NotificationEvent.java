package com.kbo.crawlerapi.domain;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_events")
public class NotificationEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "event_key", nullable = false, unique = true, length = 255)
    private String eventKey;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "delivery_status", nullable = false, length = 30)
    private String deliveryStatus;

    @Column(name = "error_message")
    private String errorMessage;

    protected NotificationEvent() {
    }

    public NotificationEvent(UUID id, Game game, String eventType, String eventKey, String title, String body, String payload) {
        this.id = id;
        this.game = game;
        this.eventType = eventType;
        this.eventKey = eventKey;
        this.title = title;
        this.body = body;
        this.payload = payload;
        this.deliveryStatus = "pending";
    }

    public UUID getId() {
        return id;
    }

    public Game getGame() {
        return game;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getPayload() {
        return payload;
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public OffsetDateTime getSentAt() {
        return sentAt;
    }

    public void markDelivery(String deliveryStatus, OffsetDateTime sentAt, String errorMessage) {
        this.deliveryStatus = deliveryStatus;
        this.sentAt = sentAt;
        this.errorMessage = errorMessage;
    }
}
