package com.salian.orchestrator.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ApprovalRecord {

    public enum Status { PENDING, APPROVED, REJECTED }

    /**
     * INTERACTIVE = a person ran {@code approve}/{@code reject}. DELEGATED = the run was started with
     * {@code --auto-approve-as <name>} (CI replay / demo); always labelled as such in audit and reports.
     */
    public enum Mode { INTERACTIVE, DELEGATED }

    private String stageId;
    private Status status = Status.PENDING;
    private Mode mode;
    private Instant requestedAt;
    private Instant decidedAt;
    private String decidedBy;
    private String comment;
    /** Exact artifact versions shown to the approver; a replan that changes them voids the approval. */
    private List<String> inputRefs = new ArrayList<>();
}
