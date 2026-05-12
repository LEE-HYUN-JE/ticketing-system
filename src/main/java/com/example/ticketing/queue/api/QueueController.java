package com.example.ticketing.queue.api;

import com.example.ticketing.queue.api.dto.QueueEntryRequest;
import com.example.ticketing.queue.api.dto.QueueEntryResponse;
import com.example.ticketing.queue.api.dto.QueueStatusResponse;
import com.example.ticketing.queue.application.QueueEntryService;
import com.example.ticketing.queue.application.QueueStatusService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/events/{eventId}/queue")
public class QueueController {

    private final QueueEntryService queueEntryService;
    private final QueueStatusService queueStatusService;

    public QueueController(QueueEntryService queueEntryService, QueueStatusService queueStatusService) {
        this.queueEntryService = queueEntryService;
        this.queueStatusService = queueStatusService;
    }

    @PostMapping
    public QueueEntryResponse enterQueue(
            @PathVariable @NotBlank(message = "eventId is required") String eventId,
            @Valid @RequestBody QueueEntryRequest request
    ) {
        return queueEntryService.enter(eventId, request.userId());
    }

    @GetMapping("/{queueToken}")
    public QueueStatusResponse getQueueStatus(
            @PathVariable @NotBlank(message = "eventId is required") String eventId,
            @PathVariable String queueToken
    ) {
        return queueStatusService.getStatus(eventId, queueToken);
    }
}
