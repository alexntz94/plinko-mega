package com.plinko.controller.admin;

import java.io.IOException;
import java.security.Principal;
import java.util.List;

import com.plinko.bo.audit.AuditService;
import com.plinko.bo.changes.ChangeRequestService;
import com.plinko.common.ListQuery;
import com.plinko.model.ChangeRequest;
import com.plinko.repo.AdminListRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ChangeAdminController {

    private static final List<String> DECISIONS = List.of("APPROVED", "REJECTED", "CANCELLED");

    private final ChangeRequestService changes;
    private final AdminListRepository lists;
    private final AuditService audit;

    public ChangeAdminController(ChangeRequestService changes, AdminListRepository lists, AuditService audit) {
        this.changes = changes;
        this.lists = lists;
        this.audit = audit;
    }

    private static AdminListRepository.ChangeFilter filter(String q, String type, String status, String proposer, String decider,
                                                           String from, String to) {
        return new AdminListRepository.ChangeFilter(q, type, status, proposer, decider, ListSupport.date(from), ListSupport.date(to));
    }

    private static ListQuery query(Integer page, Integer size, String sort, String dir) {
        return ListQuery.of(page, size, sort, dir, AdminListRepository.CHANGE_SORTS, "decided", false);
    }

    @GetMapping("/admin/changes")
    public String changes(@RequestParam(required = false) String q, @RequestParam(required = false) String type,
                          @RequestParam(required = false) String status, @RequestParam(required = false) String proposer,
                          @RequestParam(required = false) String decider, @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to, @RequestParam(required = false) Integer page,
                          @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort,
                          @RequestParam(required = false) String dir, HttpServletRequest request, Model model) {
        ListQuery query = query(page, size, sort, dir);
        AdminListRepository.ChangeFilter filter = filter(q, type, status, proposer, decider, from, to);
        model.addAttribute("pending", changes.pending());
        model.addAttribute("result", lists.changes(filter, query));
        model.addAttribute("links", ListSupport.Links.of(request, query));
        model.addAttribute("filter", filter);
        model.addAttribute("types", lists.changeTypes());
        model.addAttribute("decisions", DECISIONS);
        model.addAttribute("people", lists.changePeople());
        model.addAttribute("requireSecondApprover", changes.requireSecondApprover());
        return "admin/changes";
    }

    @GetMapping("/admin/changes.csv")
    public void changesCsv(@RequestParam(required = false) String q, @RequestParam(required = false) String type,
                           @RequestParam(required = false) String status, @RequestParam(required = false) String proposer,
                           @RequestParam(required = false) String decider, @RequestParam(required = false) String from,
                           @RequestParam(required = false) String to, @RequestParam(required = false) String sort,
                           @RequestParam(required = false) String dir, Principal principal, HttpServletResponse response) throws IOException {
        List<ChangeRequest> rows = lists.changesForExport(filter(q, type, status, proposer, decider, from, to),
                query(1, 50, sort, dir));
        audit.log(principal.getName(), "EXPORT_CHANGES", rows.size() + " decided changes");
        ListSupport.csv(response, "approvals.csv", List.of("#", "Type", "Change", "Proposed by", "Proposed (UTC)", "Decision", "Decided by",
                        "Decided (UTC)", "Note"),
                rows.stream().map(c -> List.<Object>of(c.id(), c.type(), c.summary(), c.requestedBy(), ListSupport.time(c.requestedAt()),
                        c.status(), c.decidedBy() == null ? "" : c.decidedBy(), ListSupport.time(c.decidedAt()),
                        c.decisionNote() == null ? "" : c.decisionNote())).toList());
    }

    @PostMapping("/admin/changes/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public String approve(@PathVariable long id, @RequestParam(required = false) String note, Principal principal, RedirectAttributes redirect) {
        try {
            changes.approve(id, principal.getName(), note);
            redirect.addFlashAttribute("message", "Change #" + id + " approved and applied");
        } catch (ChangeRequestService.ChangeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/changes";
    }

    /** Admins reject any change; operators can only withdraw their own. */
    @PostMapping("/admin/changes/{id}/reject")
    @PreAuthorize("hasRole('OPERATOR')")
    public String reject(@PathVariable long id, @RequestParam(required = false) String note, Principal principal,
                         org.springframework.security.core.Authentication authentication, RedirectAttributes redirect) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        boolean own = changes.pending().stream().anyMatch(c -> c.id() == id && c.requestedBy().equals(principal.getName()));
        if (!admin && !own) {
            redirect.addFlashAttribute("error", "Only admins can reject changes proposed by someone else");
            return "redirect:/admin/changes";
        }
        try {
            changes.reject(id, principal.getName(), note);
            redirect.addFlashAttribute("message", "Change #" + id + (own ? " withdrawn" : " rejected"));
        } catch (ChangeRequestService.ChangeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/changes";
    }
}
