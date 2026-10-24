package com.alphainternational.assistant

/**
 * The tools that belong to the agent loop itself (not to any service): a visible plan for multi-step jobs, and switching tool groups on.
 * They are everyday tools, they change nothing outside the job, and none of them needs an approval.
 */
val brainTools:List<Tool> = listOf(
    Tool("plan_set","For a job with 3 or more separate steps, write your plan first: 3 to 8 short steps in plain words the owner would understand (for example \"Find the three best shops\", " +
        "\"Compare their prices\", \"Write up the answer\"). The first step starts as doing. Skip this for a one- or two-step request. Setting a new plan replaces the old one.",
        obj("type" to "object","properties" to obj("items" to obj("type" to "array","items" to obj("type" to "string"))),"required" to arr(listOf("items")),"additionalProperties" to false)),
    Tool("plan_update","Mark one step of your plan. id: the step number as text, like \"2\". status: doing (you are on it now), done, skipped (not needed) or open. Update it as you go, " +
        "and finish every step before you answer. Costs nothing and sends nothing.",schema("id" to "string","status" to "string")),
    ToolGroups.enableTool()
)
