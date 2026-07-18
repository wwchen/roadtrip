package ca.floo.roadtrip.detekt

import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class RoadtripRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("roadtrip")

    override fun instance(): RuleSet =
        RuleSet(
            ruleSetId,
            mapOf(
                RuleName("TypedInfrastructurePropertyNaming") to ::TypedInfrastructurePropertyNaming,
            ),
        )
}
