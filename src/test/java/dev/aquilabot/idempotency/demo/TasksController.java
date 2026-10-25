package dev.aquilabot.idempotency.demo;

import dev.aquilabot.idempotency.Idempotent;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The annotation on the class protects every handler method. */
@Idempotent
@RestController
@RequestMapping("/tasks")
class TasksController {

    private final HandlerRuns runs;

    TasksController(HandlerRuns runs) {
        this.runs = runs;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    String create() {
        return "task " + runs.increment();
    }
}
