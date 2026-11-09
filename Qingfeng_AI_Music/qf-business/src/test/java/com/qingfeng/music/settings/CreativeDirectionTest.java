package com.qingfeng.music.settings;

import com.qingfeng.music.work.api.WorkApi.Work;
import com.qingfeng.music.common.exception.DomainException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreativeDirectionTest {
    Work work(){return new Work("work","","","","","中国风","歌曲",0,Instant.EPOCH,Instant.EPOCH,"MALE");}
    MusicSettings.Direction base(String style,String negative){return new MusicSettings.Direction(new MusicSettings.Preferences(1,null,Map.of(),"MALE","不要改写这一句",""),style,negative,"","",1);}
    @Test void theReported1013CharacterCaseKeepsEveryRequirementAndArrangement(){
        String defaults=InspirationMusicPresets.find("中国风").orElseThrow().prompt();
        var plan=new CreativePlan("需".repeat(250),"建".repeat(186),"","摘要","summary","MALE");
        var compiled=CreativeDirection.compile(work(),base(defaults,""),plan);
        assertTrue(compiled.style().length()<=1000);assertTrue(compiled.style().contains(plan.requirements()));
        assertTrue(compiled.style().contains(plan.suggestedArrangement()));assertEquals(plan,compiled.creativePlan());
        assertEquals(compiled.style().length(),compiled.compilation().styleCharacters());
    }
    @Test void removesOnlyOptionalArrangementWhenTheBudgetRunsOut(){
        var plan=new CreativePlan("需".repeat(500),"建".repeat(240),"","","","AUTO");
        var compiled=CreativeDirection.compile(work(),base("x".repeat(350),""),plan);
        assertTrue(compiled.style().contains(plan.requirements()));assertFalse(compiled.style().contains(plan.suggestedArrangement()));
        assertEquals(plan,compiled.creativePlan());assertTrue(compiled.compilation().simplified());
    }
    @Test void replacesSystemDefaultsWithCategoriesButNeverCutsUserRequirements(){
        var plan=new CreativePlan("需".repeat(600),"","","","","AUTO");
        var direction=CreativeDirection.compile(work(),base("x".repeat(700),""),plan,MusicPromptLimits.suno(),"genre: Chinese pop; 不要改写这一句");
        assertTrue(direction.style().contains(plan.requirements()));assertTrue(direction.style().contains("不要改写这一句"));
        assertTrue(direction.compilation().simplified());
        assertThrows(DomainException.class,()->CreativeDirection.compile(work(),base("x".repeat(700),""),plan));
    }
    @Test void deduplicatesExclusionsWithoutRemovingAnyDistinctConstraint(){
        var plan=new CreativePlan("","","rap, electric guitar","","","AUTO");
        var compiled=CreativeDirection.compile(work(),base("folk","rap, heavy drums"),plan);
        assertEquals("rap, heavy drums, electric guitar",compiled.negativeTags());
        var longPlan=new CreativePlan("","","b".repeat(250),"","","AUTO");
        assertThrows(DomainException.class,()->CreativeDirection.compile(work(),base("folk","a".repeat(300)),longPlan));
    }
    @Test void anEmptyPlanDoesNotChangeAnExistingPresetOrItsVoice(){
        var compiled=CreativeDirection.compile(work(),base("gentle piano, male lead vocals",""),new CreativePlan("","","","","","AUTO"));
        assertEquals("gentle piano, male lead vocals",compiled.style());assertEquals("MALE",compiled.effectiveVoice("MALE"));
    }
    @Test void defaultsCannotOverrideTheOriginalRequirementsButConfirmedChoicesCan(){
        var plan=new CreativePlan("只用钢琴","","","","","AUTO");
        var automatic=CreativeDirection.compile(work(),base("Chinese pop",""),plan,MusicPromptLimits.suno(),null,false);
        assertTrue(automatic.style().contains("Requirements take priority over defaults"));
        var chosen=CreativeDirection.compile(work(),base("Chinese pop",""),plan,MusicPromptLimits.suno(),null,true);
        assertTrue(chosen.style().contains("Current settings take priority in conflicts"));
        assertTrue(chosen.style().contains("只用钢琴"));
    }
}
