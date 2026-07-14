package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.domain.Rule;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read API of the rule catalog, consumed by remote rule-runtime instances
 * (rules.source=rest).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/rules")
@RequiredArgsConstructor
public class RuleReadController {

    private final RuleRepository ruleRepository;

    @GetMapping
    public List<Rule> list(@RequestParam(required = false) String tag) {
        var rules = ruleRepository.findAll();
        if (tag == null || tag.isBlank()) {
            return rules;
        }
        return rules.stream().filter(rule -> rule.hasTag(tag)).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Rule> get(@PathVariable String id) {
        return ruleRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
