package com.qingfeng.music.server.web.app;

import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"mysql","test"})
@Validated
@RequestMapping("/api/app/works")
public class WorkController {
    private final WorkApi works;
    private final com.qingfeng.music.server.query.WorkLibraryQuery library;
    public WorkController(WorkApi works,com.qingfeng.music.server.query.WorkLibraryQuery library) { this.works=works;this.library=library; }
    public record DraftRequest(@NotNull @Size(max=80) String recipient,@NotNull @Size(max=32) String occasion,
        @NotNull @Size(max=4000) String story,@NotNull @Size(max=500) String message,@NotNull @Size(max=80) String style,@NotNull @Size(max=100) String title,
        @PositiveOrZero Long expectedVersion,@Pattern(regexp="[0-9a-fA-F-]{36}") String draftKey,@Pattern(regexp="AUTO|MALE|FEMALE")String vocalGender) {
        WorkApi.Draft draft() { return new WorkApi.Draft(recipient,occasion,story,message,style,title,vocalGender); }
    }
    @PostMapping public WorkApi.Work create(@AuthenticationPrincipal CreatorPrincipal creator,@RequestBody @Valid DraftRequest request) { return request.draftKey()==null?works.create(creator.id(),request.draft()):works.create(creator.id(),request.draft(),request.draftKey()); }
    @GetMapping public List<WorkApi.Work> list(@AuthenticationPrincipal CreatorPrincipal creator,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int page) { return works.list(creator.id(),page); }
    @GetMapping("/library") public List<com.qingfeng.music.server.query.WorkLibraryQuery.Item> library(@AuthenticationPrincipal CreatorPrincipal creator,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int page) { return library.list(creator.id(),page); }
    @GetMapping("/{id}") public WorkApi.Work get(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id) { return works.get(creator.id(),id); }
    @DeleteMapping("/{id}") public WorkApi.Work delete(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id) { return works.delete(creator.id(),id); }
    @PutMapping("/{id}") public WorkApi.Work update(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id,@RequestBody @Valid DraftRequest request) {
        if(request.expectedVersion()==null) throw new com.qingfeng.music.common.exception.DomainException("INVALID_DRAFT","更新草稿必须提供版本号");
        return works.update(creator.id(),id,request.expectedVersion(),request.draft());
    }
}
