package com.example.managementservice.controller;

import com.example.managementservice.config.ActivityLogger;
import com.example.managementservice.config.multitenant.UserChecker;
import com.example.managementservice.exchange.request.*;
import com.example.managementservice.exchange.response.*;
import com.example.managementservice.exchange.response.ApiResponseHandler;
import com.example.managementservice.exchange.response.CommentResponse;
import com.example.managementservice.exchange.response.IssueResponse;
import com.example.managementservice.exchange.response.PaginatedResponse;
import com.example.managementservice.imap.IssueFromEmailService;
import com.example.managementservice.model.Comments;
import com.example.managementservice.model.EmailConfiguration;
import com.example.managementservice.model.Issue;
import com.example.managementservice.service.IssueService;
import com.example.managementservice.utils.AppConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.parameters.P;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/issues")
@Tag(name = "Issue Management", description = "APIs for managing issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;
    private final ActivityLogger activityLogger;
    private final IssueFromEmailService issueFromEmailService;
    private final UserChecker userChecker;

    @Operation(summary = "Create a new issue", description = "Creates a new issue in the system")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Issue created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid input provided")
    })
    @PreAuthorize("@userChecker.canModifyProject(#issueRequest.projectId)")
    @PostMapping(value = "/create")
    public ResponseEntity<ApiResponseHandler<Object>> createIssue(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @Valid @RequestBody @Parameter(description = "Details of the issue to be created", required = true) IssueRequest issueRequest, @AuthenticationPrincipal Jwt jwt) {
        Issue issue = issueService.createIssue(issueRequest, jwt);
        String entityName = issueRequest.getClass().getSimpleName().replaceAll("(Request|DTO|Response)$", "");
        activityLogger.logActivity(null, issue, jwt, entityName);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponseHandler<>(true, "Issue created successfully"));
    }

    @PreAuthorize("@userChecker.canModifyProject(#quickIssueRequest.projectId)")
    @PostMapping(value = "/create/quick-ticket")
    @Operation(summary = "Create a quick issue", description = "Creates a quick issue in the system")
    public ResponseEntity<ApiResponseHandler<Object>> createQuickIssue(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @Valid @RequestBody @Parameter(description = "Details of the quick issue to be created", required = true) QuickIssueRequest quickIssueRequest, @AuthenticationPrincipal Jwt jwt) {
        Issue newIssue = issueService.createQuickIssue(quickIssueRequest, jwt);
        String entityName = quickIssueRequest.getClass().getSimpleName().replaceAll("(Request|DTO|Response)$", "");
        activityLogger.logActivity(null, newIssue, jwt, entityName);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponseHandler<>(true, "Issue created successfully"));
    }

    @Operation(summary = "Get all issues", description = "Retrieves a paginated list of all issues with sorting capabilities")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved the list of issues"),
            @ApiResponse(responseCode = "400", description = "Invalid page, size or sort parameters")
    })
    @GetMapping
    public ResponseEntity<ApiResponseHandler<Page<IssueResponse>>> getAllIssues(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @RequestParam(required = false) List<String> statusIds,
            @RequestParam(required = false) List<UUID> priorityIds,
            @RequestParam(required = false) List<String> workTypeIds,
            @RequestParam(required = false) List<String> reporters,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String sprintId,
            @RequestParam(required = false) String searchText,
            @Parameter(description = "Page number (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Number of records per page", example = "10") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "Field to sort by", example = "createdAt") @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Sort direction ('asc' or 'desc')", example = "asc", schema = @Schema(allowableValues = {"asc", "desc"})) @RequestParam(defaultValue = "asc") String direction) {

        Sort.Direction sortDirection = direction.equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sortBy));
        Page<IssueResponse> issues = issueService.getAllIssues(statusIds, priorityIds, workTypeIds, reporters, assignedTo, projectId, sprintId, searchText, pageable);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Issues fetched successfully", issues));
    }

    @PostMapping(value = "/all")
    @Operation(summary = "Get all issues with pagination", description = "Retrieves all issues grouped by status with pagination and sorting capabilities")
    public ResponseEntity<ApiResponseHandler<Map<String, PaginatedResponse<IssueDetails>>>> getAllIssuesWithPagination(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @RequestParam(required = false) List<String> statusIds,
            @RequestParam(required = false) List<UUID> priorityIds,
            @RequestParam(required = false) List<String> workTypeIds,
            @RequestParam(required = false) List<String> reporters,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String sprintId,
            @RequestParam(required = false) String searchText,
            @RequestParam(defaultValue = "5") int perStatusLimit,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestBody(required = false) Map<String, Integer> statusPageMap
    ) {
        Sort.Direction sortDirection = direction.equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort sort = Sort.by(sortDirection, sortBy);

        Map<String, PaginatedResponse<IssueDetails>> issues = issueService.getGroupedIssuesByStatus(statusIds, perStatusLimit, statusPageMap, priorityIds, workTypeIds, reporters, assignedTo, projectId, sprintId, searchText, sort);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Issues fetched successfully", issues));
    }

    @Operation(
            summary = "Get User Issues",
            description = "Fetches all issues for a specific user within a project."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Issues fetched successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid input parameters"),
            @ApiResponse(responseCode = "404", description = "User or project not found")
    })
    @GetMapping(value = "/user-issues/{id}/{projectId}")
    public ResponseEntity<ApiResponseHandler<List<IssueResponse>>> getUserIssues(
            @Parameter(description = "User ID", required = true) @PathVariable String id,
            @Parameter(description = "Project ID", required = true) @PathVariable UUID projectId) {
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Issues fetched successfully", issueService.getUserIssues(id, projectId)));
    }

    @Operation(summary = "Get Issue By Key", description = "Retrieves an issue by its key")
    @GetMapping(value = "/key")
    public ResponseEntity<ApiResponseHandler<IssueResponse>> getIssueByKey(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @Parameter(description = "The key of the issue to be retrieved", required = true, example = "12345") @RequestParam Long key, @RequestParam UUID projectId) {
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Issue fetched by key successfully", issueService.getIssueByKey(key, projectId)));
    }

    @Operation(summary = "Get issues by reporter", description = "Retrieves a list of issues created by a specific reporter")
    @GetMapping(value = "/reporter")
    public ResponseEntity<ApiResponseHandler<PaginatedResponse<IssueResponse>>> getIssueByReporter(@RequestParam String reporter,
                                                                 @RequestParam UUID projectId,
                                                                 @Parameter(description = "Page number (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
                                                                 @Parameter(description = "Number of records per page", example = "10") @RequestParam(defaultValue = "10") int size,
                                                                 @Parameter(description = "Field to sort by", example = "createdAt") @RequestParam(defaultValue = "createdAt") String sortBy,
                                                                 @Parameter(description = "Sort direction ('asc' or 'desc')", example = "asc", schema = @Schema(allowableValues = {"asc", "desc"})) @RequestParam(defaultValue = "asc") String direction) {

        Sort.Direction sortDirection = direction.equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sortBy));
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "All issues fetched by Reporter", issueService.getAllIssuesByReporter(reporter, projectId, pageable)));
    }

    @Operation(summary = "Get issue by ID", description = "Retrieves an issue by its ID")
    @GetMapping(value = "/{id}")
    public ResponseEntity<ApiResponseHandler<IssueResponse>> getIssueById(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @Parameter(description = "The UUID of the issue to be retrieved", required = true) @PathVariable UUID id) {
        IssueResponse issue = issueService.getIssueResponseById(id);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Issue fetched successfully", issue));
    }

    @Operation(summary = "Get child issues", description = "Retrieves all child issues of a given issue")
    @GetMapping(value = "/parent/{id}")
    public ResponseEntity<ApiResponseHandler<ParentIssueDetails>> getChildIssues(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @Parameter(description = "The UUID of the parent issue to fetch child issues", required = true) @PathVariable UUID id) {
        ParentIssueDetails issues = issueService.getAllIssuesByParentId(id);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "All the child issues fetched successfully", issues));
    }

    @Operation(summary = "Get issue priority", description = "Retrieves issues by priority")
    @GetMapping(value = "/priority/{id}")
    public ResponseEntity<ApiResponseHandler<List<IssueResponse>>> getPriority(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @Parameter(description = "The UUID of the issue to retrieve priority", required = true) @PathVariable UUID id) {
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Get the priority", issueService.getIssueByPriority(id)));
    }

    @Operation(
            summary = "Get issues by status",
            description = "Retrieves all issues of a given status within a specific project."
    )
    @GetMapping(value = "/status/{statusId}/project/{projectId}")
    public ResponseEntity<ApiResponseHandler<List<IssueResponse>>> getIssueByStatusId(
            @Parameter(description = "The UUID of the status to fetch issues", required = true)
            @PathVariable String statusId,

            @Parameter(description = "The UUID of the project to filter issues", required = true)
            @PathVariable UUID projectId) {

        List<IssueResponse> issues = issueService.getAllIssuesByStatusId(statusId, projectId);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "All issues fetched successfully", issues));
    }


    @Operation(summary = "Get issues related to project", description = "Retrieves all issues of a given project, or only parent issues if specified")
    @GetMapping(value = "/project/{id}")
    public ResponseEntity<ApiResponseHandler<List<IssueResponse>>> getIssuesByProjectId(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @Parameter(description = "The UUID of the project to fetch issues", required = true) @PathVariable UUID id,
            @Parameter(description = "Flag to specify if only parent issues should be retrieved", example = "false") @RequestParam(defaultValue = "false") boolean parentOnly) {
        List<IssueResponse> issues;

        if (parentOnly) {
            issues = issueService.getAllParentIssueInProject(id);
        } else {
            issues = issueService.getAllIssueByProjectId(id);
        }

        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Issues fetched successfully", issues));
    }

    @Operation(summary = "Get issues grouped by status", description = "Fetches up to 10 issues for each status in the project's configuration")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Issues fetched successfully"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    @GetMapping(value = "/project/{id}/issues-by-status")
    public ResponseEntity<ApiResponseHandler<Map<Object, Object>>> getIssuesByStatus(
            @Parameter(description = "The UUID of the project to fetch issues for", required = true) @PathVariable UUID id,
            @Parameter(description = "The UUID of the Status to fetch issues for", required = false) @RequestParam(required = false) String statusId,
            @Parameter(description = "Page number (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Number of records per page", example = "10") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "Field to sort by", example = "createdAt") @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Sort direction ('asc' or 'desc')", example = "asc", schema = @Schema(allowableValues = {"asc", "desc"})) @RequestParam(defaultValue = "asc") String direction) {

        Sort.Direction sortDirection = direction.equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sortBy));

        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Issues fetched successfully", issueService.findAllIssuesByStatus(id, statusId, pageable)));
    }

    @PreAuthorize("@userChecker.canModifyProject(#issueRequest.projectId)")
    @Operation(summary = "Update an issue", description = "Updates an existing issue by ID")
    @PutMapping(value = "/{id}")
    public ResponseEntity<ApiResponseHandler<Object>> updateIssue(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @Parameter(description = "The UUID of the issue to be updated", required = true) @PathVariable UUID id,
            @Valid @RequestBody @Parameter(description = "Details of the issue to be updated", required = true) IssueRequest issueRequest, @AuthenticationPrincipal Jwt jwt) {
        UpdateRequest<Issue> updateRequest = issueService.updateIssue(id, issueRequest, jwt);
        String entityName = issueRequest.getClass().getSimpleName().replaceAll("(Request|DTO|Response)$", "");
        activityLogger.logActivity(updateRequest.getOldValue(), updateRequest.getUpdatedValue(), jwt, entityName);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Issue updated successfully"));
    }

    @Operation(summary = "Delete an issue", description = "Deletes an issue by its ID")
    @DeleteMapping(value = "/{id}")
    public ResponseEntity<ApiResponseHandler<Object>> deleteIssue(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @Parameter(description = "The UUID of the issue to be deleted", required = true) @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) throws Exception {
        userChecker.checkProjectAccessOrThrow(projectIdHeader);
       Issue issue = issueService.deleteIssue(id, jwt);
       String entityName = issue.getClass().getSimpleName().replaceAll("(Request|DTO|Response)$", "");
       activityLogger.logActivity(issue, null, jwt, entityName);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Issue deleted successfully"));
    }

    /**
     * Adds a comment to a specific issue.
     *
     * @param id             The UUID of the issue to which the comment will be added.
     * @param commentRequest The request body containing the comment details.
     * @return A ResponseEntity containing ApiResponseHandler with the status of the operation.
     */
    @PostMapping(value = "/comment/{id}")
    @Operation(summary = "Add a comment to an issue", description = "Adds a comment to a specific issue by its ID")
    public ResponseEntity<ApiResponseHandler<Comments>> addComment(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @Parameter(description = "The UUID of the issue to add comment", required = true)
            @PathVariable UUID id,
            @Valid
            @RequestBody
            @Parameter(description = "Comment details including the content, commented by, and timestamp", required = true)
            CommentRequest commentRequest,
            @AuthenticationPrincipal Jwt jwt) {
        userChecker.checkProjectAccessOrThrow(projectIdHeader);
        String entityName = commentRequest.getClass().getSimpleName().replaceAll("(Request|DTO|Response)$", "");
        activityLogger.logActivity(null, commentRequest.getContent(), jwt, entityName);

        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Comment added successfully", issueService.addComment(id, commentRequest, jwt)));
    }

    @PutMapping(value = "/comment/{id}")
    public ResponseEntity<ApiResponseHandler<UpdateRequest<Object>>> updateComment(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @Parameter(description = "The UUID of the comment to be updated", required = true)
            @PathVariable UUID id,
            @Valid
            @RequestBody
            @Parameter(description = "Updated comment details including the content and timestamp", required = true)
            CommentRequest commentRequest,
            @AuthenticationPrincipal Jwt jwt) {
        userChecker.checkProjectAccessOrThrow(projectIdHeader);
        UpdateRequest comments = issueService.updateComment(id, commentRequest, jwt);
        String entityName = commentRequest.getClass().getSimpleName().replaceAll("(Request|DTO|Response)$", "");
        activityLogger.logActivity(comments.getOldValue(), comments.getUpdatedValue(), jwt, entityName);

        return ResponseEntity.ok(new ApiResponseHandler<UpdateRequest<Object>>(true, "Comment updated successfully", comments));
    }

    @DeleteMapping(value = "/comment/{id}")
    @Operation(summary = "Delete a comment", description = "Deletes a comment by its ID")
    public ResponseEntity<ApiResponseHandler<Object>> deleteComment(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @Parameter(description = "The UUID of the comment to be deleted", required = true)
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        userChecker.checkProjectAccessOrThrow(projectIdHeader);
        Comments comments = issueService.deleteComment(id, jwt);
        String details = String.format("%s deleted Comment: \"%s\" | CommentId: %s",
                jwt.getClaimAsString("preferred_username"),
                comments.getComment(),
                comments.getCommentId());
        String entityName = comments.getClass().getSimpleName().replaceAll("(Request|DTO|Response)$", "");
        activityLogger.logActivity(details, null, jwt, entityName);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Comment deleted successfully"));
    }

    @GetMapping(value = "/{id}/comments")
    @Operation(summary = "Get all comments by issue", description = "Retrieves all comments associated with a specific issue")
    public ResponseEntity<ApiResponseHandler<List<CommentResponse>>> getAllCommentsByIssue(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @Parameter(description = "The UUID of the issue to get comments", required = true)
            @PathVariable UUID id) {
        List<CommentResponse> comments = issueService.getComments(id);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Comments fetched successfully", comments));
    }

    @PatchMapping(value = "/update-status")
    @Operation(summary = "Update issue status", description = "Updates the status of an existing issue")
    public ResponseEntity<ApiResponseHandler<Object>> updateStatusOfIssue(@RequestBody @Valid StatusUpdateRequest statusUpdateRequest, @AuthenticationPrincipal Jwt jwt) {
        userChecker.denyIfProjectCompletedByIssueId(statusUpdateRequest.getIssueId());
        UpdateRequest updateRequest = issueService.updateIssueStatus(statusUpdateRequest);
        String entityName = updateRequest.getClass().getSimpleName().replaceAll("(Request|DTO|Response)$", "");
        activityLogger.logActivity(updateRequest.getOldValue(), updateRequest.getUpdatedValue(), jwt, entityName);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Issue status updated successfully"));

    }

    @GetMapping(value = "/search")
    @Operation(summary = "Search for issues", description = "Search for issues with pagination")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "issues retrieved successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid input provided")
    })
    public ResponseEntity<ApiResponseHandler<PaginatedResponse<Issue>>> searchProjects(
            @RequestHeader(value = AppConstants.PROJECT_ID_HEADER, required = false) String projectIdHeader,
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "title") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir,
            @RequestParam(defaultValue = "false") UUID projectId
    ) {
        Sort.Direction direction = sortDir.equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        PaginatedResponse<Issue> issues = issueService.searchIssues(query, pageable, projectId);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Issues retrieved successfully", issues));
    }

    @Operation(summary = "Get issue and project counts", description = "Retrieves total issue, project count and categorized counts")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved issue and project counts")
    })
    @GetMapping(value = "/counts")
    public ResponseEntity<?> getCounts(
            @RequestHeader(AppConstants.X_TENANT_ID_HEADER) String subdomain,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) UUID projectId) {

        Map<String, Object> data = issueService.getCounts(fromDate, toDate, projectId, subdomain);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Successfully retrieved counts", data));
    }

    @PostMapping(value = "/upload-attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload attachments", description = "Uploads multiple attachments to MinIO and returns their details")
    public ResponseEntity<ApiResponseHandler<List<AttachmentRequest>>> uploadAttachments(
            @RequestPart(value = "attachments", required = false) List<MultipartFile> attachments,
            @AuthenticationPrincipal Jwt jwt) {
        List<AttachmentRequest> uploadedFiles = issueService.uploadAttachments(attachments, jwt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponseHandler<>(true, "Attachments uploaded successfully", uploadedFiles));
    }

    @Operation(summary = "Delete an attachment", description = "Deletes an attachment from MinIO and the database")
    @DeleteMapping(value = "/attachments/{attachmentId}")
    public ResponseEntity<ApiResponseHandler<Object>> deleteAttachment(
            @PathVariable UUID attachmentId,
            @AuthenticationPrincipal Jwt jwt) {

        issueService.deleteAttachment(attachmentId, jwt);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Attachment deleted successfully"));
    }

    @Operation(summary = "Delete an attachment in S3", description = "Deletes an attachment in S3 bucket")
    @DeleteMapping(value = "/delete-attachment")
    public ResponseEntity<ApiResponseHandler<Object>> deleteAttachment(@RequestParam String fileName){
        issueService.deleteFiles(fileName);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Attachment deleted in bucket successfully"));
    }

    @GetMapping(value = "/download")
    @Operation(summary = "Download a file", description = "Downloads a file from the server")
    public ResponseEntity<byte[]> downloadFile(@RequestParam String fileName) throws FileNotFoundException {
        byte[] fileData = issueService.downloadFile(fileName);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(fileData);
    }

    @PostMapping(value = "/email-configs")
    @Operation(summary = "Create Email Configuration", description = "Creates a new email configuration for issue management")
    public ResponseEntity<EmailConfiguration>createEmailConfiguration(@RequestBody EmailConfiguration emailConfiguration){
        return issueService.createEmailConfiguration(emailConfiguration);
    }

    @GetMapping(value = "/user/{userId}/project/{projectId}")
    @Operation(summary = "Get issues by user ID", description = "Retrieves a paginated list of issues assigned to a specific user in a project")
    public ResponseEntity<ApiResponseHandler<PaginatedResponse<IssueSummaryResponse>>> getIssuesByUser(
            @PathVariable String userId,
            @PathVariable UUID projectId,
            @PageableDefault(page = 0, size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        PaginatedResponse<IssueSummaryResponse> issues = issueService.getIssuesByUserAndProject(userId, projectId, pageable);
        return ResponseEntity.ok(new ApiResponseHandler<>(true, "Issues fetched successfully", issues));
    }
}