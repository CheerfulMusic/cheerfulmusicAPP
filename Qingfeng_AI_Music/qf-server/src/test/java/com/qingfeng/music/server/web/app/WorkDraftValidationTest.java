package com.qingfeng.music.server.web.app;

import com.qingfeng.music.work.api.WorkApi;
import org.junit.jupiter.api.Test;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 作品风格的长度约束：接口层上限与 music_work.style 的列宽（80）一致。
 * 参数编译阶段必须先裁剪，否则这里会直接拒绝整个草稿。
 */
class WorkDraftValidationTest {
    private static String body(String style) {
        return "{\"recipient\":\"\",\"occasion\":\"\",\"story\":\"\",\"message\":\"\",\"style\":\"" + style + "\",\"title\":\"歌名\"}";
    }

    @Test void refusesAStyleLongerThanTheStorageColumn() throws Exception {
        var works = mock(WorkApi.class);
        var controller = new WorkController(works, mock(com.qingfeng.music.server.query.WorkLibraryQuery.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setValidator(new LocalValidatorFactoryBean()).build();
        mvc.perform(post("/api/app/works").contentType("application/json").content(body("x".repeat(81))))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(works);
    }

    @Test void acceptsAStyleExactlyAtTheColumnWidth() {
        // 精确定位上限：80 必须放行，81 必须被拒，与 music_work.style 的列宽一致。
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        assertTrue(validator.validate(new WorkController.DraftRequest("","","","","x".repeat(80),"歌名",null,null,null)).isEmpty());
        assertFalse(validator.validate(new WorkController.DraftRequest("","","","","x".repeat(81),"歌名",null,null,null)).isEmpty());
        // 声音取值同样受白名单约束。
        assertFalse(validator.validate(new WorkController.DraftRequest("","","","","温暖民谣","歌名",null,null,"ROBOT")).isEmpty());
        assertTrue(validator.validate(new WorkController.DraftRequest("","","","","温暖民谣","歌名",null,null,"FEMALE")).isEmpty());
    }
}
