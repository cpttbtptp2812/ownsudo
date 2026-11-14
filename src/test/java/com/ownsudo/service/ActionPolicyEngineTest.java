package com.ownsudo.service;

import com.ownsudo.entity.AgentToolEntity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActionPolicyEngineTest {
    private final ActionPolicyEngine engine = new ActionPolicyEngine();
    @Test void unknownToolIsDenied() {
        var result=engine.evaluate(null);
        assertEquals("DENY",result.decision()); assertEquals("DENIED",result.actionStatus());
        assertEquals("TOOL_NOT_REGISTERED",result.reasonCode());
    }
    @Test void disabledToolIsDenied() {
        AgentToolEntity tool=tool("HIGH",false);
        assertEquals("DENY",engine.evaluate(tool).decision());
    }
    @Test void readOnlyToolIsAllowedWithoutApproval() {
        var result=engine.evaluate(tool("READ_ONLY",true));
        assertEquals("ALLOW",result.decision()); assertEquals("READY",result.actionStatus());
    }
    @Test void lowRiskToolIsAllowedWithoutApproval() {
        assertEquals("ALLOW",engine.evaluate(tool("LOW",true)).decision());
    }
    @Test void mediumAndHigherRiskToolsRequireApproval() {
        for(String risk:new String[]{"MEDIUM","HIGH","CRITICAL"}) {
            var result=engine.evaluate(tool(risk,true));
            assertEquals("REQUIRE_APPROVAL",result.decision());
            assertEquals("PENDING_APPROVAL",result.actionStatus());
            assertEquals(risk,result.riskLevel());
        }
    }
    private AgentToolEntity tool(String risk,boolean enabled) {
        AgentToolEntity tool=new AgentToolEntity(); tool.setRiskLevel(risk); tool.setEnabled(enabled); return tool;
    }
}
