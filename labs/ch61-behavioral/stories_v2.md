# Version 2: rewrites of the two weakest stories, plus a new story for the gap L1 found

## S3 My failed search migration
- competencies: failure, ownership, learning
- level: B3
- situation: I led the cutover of product search to a new engine version. About 30% of our queries are in Hindi or Hinglish.
- task: I owned the plan and the go/no-go decision, with a goal of no visible relevance drop.
- action: I built the shadow comparison, but I only sampled English queries, and I signed off on it. Two hours after the cutover, support flagged bad Hindi results. I made the call to roll back, then led the analysis: the new analyzer tokenised Devanagari differently. I added a language-stratified query sample and a click-through guardrail to the shadow test, fixed the analyzer config, and re-ran the cutover three weeks later.
- result: The second cutover held Hindi click-through within 1% of the old engine and improved English by 4%. The stratified shadow test is now the template for every search change.
- learning: I now ask "which users does this sample not represent?" before signing off any comparison, and I say out loud which go/no-go criteria are mine.

## S8 Customer-facing delay in slot booking
- competencies: customer-focus, ambiguity, technical-decision
- level: B2
- situation: Customers who returned to change a delivery slot saw their own slot as full. Support tickets about it had grown to about 900 a week.
- task: I owned the slot booking service and took the ticket theme on even though the root cause was unclear.
- action: I reproduced the journey end to end rather than testing the API alone, and found that the capacity check counted the customer's own reservation. I changed the query to exclude it, added a test for the modify flow, and asked support to tag slot tickets so we could see the trend.
- result: Slot-related tickets fell from about 900 to under 60 a week within two weeks, and modify-order completion rose by 7%.
- learning: I test changes as a customer journey now, not only as API calls, and I ask support for tagged tickets before and after a fix.

## S9 Cutting the quarter's roadmap in half
- competencies: prioritisation, strategy, conflict, org-change
- level: B4
- situation: Our platform group of five teams had 23 committed roadmap items for the quarter and delivered about 40% of commitments in the previous two quarters.
- task: As the group's senior architect, I was asked by the director to make the plan credible without losing the business's trust.
- action: I scored every item on customer impact, risk reduction and cost of delay with each product manager, published the scores openly, and proposed committing to 11 items with the rest as stretch. I met the two stakeholders whose items dropped before the review, offering them dates and interim workarounds. I also changed our planning so that each team reserves 20% for operational work, which had been silently eating the roadmap.
- result: We delivered 10 of the 11 commitments that quarter and 85% the quarter after. Escalations about missed dates dropped from about six a month to one.
- learning: Saying no with published criteria and a date for "later" keeps trust; hidden overcommitment loses it.
