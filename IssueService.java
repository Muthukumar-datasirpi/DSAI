package com.example.managementservice.service;

import com.example.managementservice.exchange.request.*;
import com.example.managementservice.exchange.response.*;
import com.example.managementservice.model.Comments;
import com.example.managementservice.model.EmailConfiguration;
import com.example.managementservice.model.Issue;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Sort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface IssueService {

    Issue createIssue(IssueRequest issueDTO, Jwt jwt);

    UpdateRequest<Issue> updateIssue(UUID issueId, IssueRequest issueDTO, Jwt jwt);

    IssueResponse getIssueResponseById(UUID issueId);

    Issue getIssueById(UUID issueId);

    Issue deleteIssue(UUID issueId, Jwt jwt) throws Exception;
  
    Map<String, PaginatedResponse<IssueDetails>> getGroupedIssuesByStatus(List<String> statusIds, int perStatusLimit, Map<String, Integer> statusPageMap, List<UUID> priorityIds, List<String> workTypeIds, List<String> reporters, String assignedTo, UUID projectId, String sprintId, String searchText, Sort sort);

    ParentIssueDetails getAllIssuesByParentId(UUID id);

    List<IssueResponse> getAllIssueByProjectId(UUID projectId);

    List<IssueResponse> getAllIssuesByStatusId(String statusId, UUID projectId);

    List<IssueResponse> getIssueByPriority(UUID id);

    IssueResponse getIssueByKey(Long key, UUID projectId);

    List<IssueResponse> getAllParentIssueInProject(UUID projectId);

    Comments addComment(UUID id, @Valid CommentRequest commentRequest, Jwt jwt);

    UpdateRequest updateComment(UUID id, @Valid CommentRequest commentRequest, Jwt jwt);

    Comments deleteComment(UUID id, Jwt jwt) throws Exception;

    List<CommentResponse> getComments(UUID id);

    UpdateRequest updateIssueStatus(@Valid StatusUpdateRequest statusUpdateRequest);

    List<IssueResponse> getUserIssues(String id, UUID projectId);

    Map<String, Object> getCounts(LocalDate fromDate, LocalDate toDate, UUID projectId, String subdomain);

    PaginatedResponse<Issue> searchIssues(String query, Pageable pageable, UUID ProjectId);

    Map<Object, Object> findAllIssuesByStatus(UUID id, String statusId, Pageable pageable);

    PaginatedResponse<IssueResponse> getAllIssuesByReporter(String reporter, UUID projectId, Pageable pageable);

    Issue createQuickIssue(QuickIssueRequest quickIssueRequest, Jwt jwt);

    List<AttachmentRequest> uploadAttachments(List<MultipartFile> attachments, Jwt jwt);

    void deleteAttachment(UUID attachmentId, Jwt jwt);

    void deleteFiles(String fileName);

    byte[] downloadFile(String fileName) throws FileNotFoundException;

    ResponseEntity<EmailConfiguration> createEmailConfiguration(EmailConfiguration emailConfiguration);

    void populateContributorStatistics(Map<String, Object> response,
                                       LocalDateTime fromDateTime,
                                       LocalDateTime toDateTime,
                                       UUID projectId, String subdomain);

    Page<IssueResponse> getAllIssues(List<String> statusIds, List<UUID> priorityIds, List<String> workTypeIds, List<String> reporters, String assignedTo, UUID projectId, String sprintId, String searchText, Pageable pageable);

    Issue createIssueFromRequest(CreateTicketRequest request);

    PaginatedResponse<IssueSummaryResponse> getIssuesByUserAndProject(String userId, UUID projectId, Pageable pageable);
}