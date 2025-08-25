package com.example.managementservice.exchange.request;

import com.example.managementservice.exchange.request.enums.SprintBacklogType;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Data
public class IssueRequest {

    @NotBlank(message = "Issue name cannot be blank")
    @Size(max = 255, message = "Issue name should not exceed 255 characters")
    private String title;

    private String description;

    @Size(max = 300, message = "Summary should not exceed 300 characters")
    private String summary;

    @NotNull(message = "Project ID cannot be null")
    private UUID projectId;

    private String assignedTo;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private Date startDate;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private Date endDate;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private Date originalEstimateDate;

    private String reporter;

    @NotNull(message = "Priority ID cannot be null")
    private UUID priorityId;

    @NotBlank(message = "Status ID cannot be null")
    private String statusId;

    private UUID parentIssueId;

    @NotNull(message = "Work Type ID cannot be null")
    private String workTypeId;

    private List<AttachmentRequest> attachments;
    private List<IssueCustomFieldValues> customFieldValues;

    private Integer storyPoints;
    private SprintBacklogType type;
}