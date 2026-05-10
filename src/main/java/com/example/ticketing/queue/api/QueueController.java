package com.example.ticketing.queue.api;

import com.example.ticketing.queue.api.QueueEntryDtos.QueueEntryRequest;
import com.example.ticketing.queue.api.QueueEntryDtos.QueueEntryResponse;
import com.example.ticketing.queue.application.QueueEntryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    public QueueController(QueueEntryService queueEntryService) {
        this.queueEntryService = queueEntryService;
    }

    @PostMapping
    public QueueEntryResponse enterQueue(
            @PathVariable @NotBlank(message = "eventId is required") String eventId,
            @Valid @RequestBody QueueEntryRequest request
    ) {
        return queueEntryService.enter(eventId, request.userId());
    }
}

