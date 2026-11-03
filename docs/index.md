---
title: Home
---

<div class="pb-hero" markdown>
<div markdown>

<span class="pb-kicker">Java 25 · Spring Boot 4 · beginner to architect</span>

<h1>System Design Enterprise Handbook</h1>

<p class="pb-lead">From your first <code>@RestController</code> to defending a national-scale architecture in a Principal Engineer review, with production code, design documents and interview answers.</p>

[How to use this handbook](00-orientation/00-how-to-use-this-handbook.md){ .md-button .md-button--primary }
[Case studies](05-case-studies/38-url-shortener-and-short-links.md){ .md-button }
[Interview framework](07-interview/57-the-system-design-interview-framework.md){ .md-button }

</div>
<div class="pb-stack" aria-hidden="true">
<div class="pb-stack-plane">
<div class="pb-layer l-cpu">Data layer<small>databases · queues · object storage</small></div>
<div class="pb-layer l-car">Services &amp; caches<small>stateless · replicated · cached</small></div>
<div class="pb-layer l-fjp">Edge<small>CDN · load balancer · API gateway</small></div>
<div class="pb-layer l-vt">Clients<small>web · mobile · partners</small></div>
</div>
<div class="pb-stack-legend">The tiers every design in this<br>handbook is built from</div>
</div>
</div>

<div class="pb-stats">
<div class="pb-stat"><b>88</b><span>pages</span></div>
<div class="pb-stat"><b>10</b><span>sections</span></div>
<div class="pb-stat"><b>323</b><span>diagrams</span></div>
<div class="pb-stat"><b>222</b><span>Java source files</span></div>
</div>

## Explore the handbook

<div class="pb-cards" markdown>

<div class="pb-card" markdown>
<span class="pb-icon">:material-compass:</span>

[Orientation](00-orientation/00-how-to-use-this-handbook.md){ .pb-title }

How to Use This Handbook, What System Design Is…
</div>

<div class="pb-card" markdown>
<span class="pb-icon">:material-sprout:</span>

[Foundations](01-foundations/02-back-of-the-envelope-estimation.md){ .pb-title }

Back-of-the-Envelope Estimation, Networking Essentials, API Design, Relational Databases Deep Dive…
</div>

<div class="pb-card" markdown>
<span class="pb-icon">:material-lan:</span>

[Distributed Core](02-distributed-core/10-replication.md){ .pb-title }

Replication, Partitioning & Sharding, Consistency, Time & Consensus, Distributed Transactions…
</div>

<div class="pb-card" markdown>
<span class="pb-icon">:material-factory:</span>

[Production](03-production/21-data-at-scale.md){ .pb-title }

Data at Scale, Search Systems, Security Architecture, Observability…
</div>

<div class="pb-card" markdown>
<span class="pb-icon">:material-city-variant:</span>

[Architect](04-architect/31-architecture-decision-making.md){ .pb-title }

Architecture Decision Making, Design Documents & Architecture Communication, Domain-Driven Design, Modern Architecture Styles…
</div>

<div class="pb-card" markdown>
<span class="pb-icon">:material-briefcase-search:</span>

[Case Studies](05-case-studies/38-url-shortener-and-short-links.md){ .pb-title }

URL Shortener & SMS Short Links, Distributed Rate Limiter, Notification System, Chat — Customer–Rider Messaging…
</div>

<div class="pb-card" markdown>
<span class="pb-icon">:material-trophy:</span>

[Capstone](06-capstone/56-capstone-grocery-marketplace-end-to-end.md){ .pb-title }

The Grocery Marketplace, End to End…
</div>

<div class="pb-card" markdown>
<span class="pb-icon">:material-account-tie:</span>

[Interview](07-interview/57-the-system-design-interview-framework.md){ .pb-title }

The System Design Interview Framework, Level Expectations, Interview Q&A Bank, Mock Interviews…
</div>

<div class="pb-card" markdown>
<span class="pb-icon">:material-office-building:</span>

[Industry Designs](08-industry/index.md){ .pb-title }

I01. Amazon-Style Global Marketplace, I01b. Amazon — How It Works, I02. Flipkart-Style Sale-Day E-Commerce, I02b. Flipkart — How It Works…
</div>

<div class="pb-card" markdown>
<span class="pb-icon">:material-bookshelf:</span>

[Appendix](99-appendix/qa-index.md){ .pb-title }

Glossary, Lab Catalogue…
</div>

</div>

This handbook teaches system design the way it is practised in enterprise Java shops. Every concept is explained from intuition to internals. It is implemented in Java 25 / Spring Boot 4, stress-tested against production failure modes, and turned into interview answers that hold up at the 15-year level.

!!! abstract "What makes this handbook different"
    - **One domain, end to end.** Every chapter uses the same multi-vendor grocery marketplace for India: OTP login, pincode serviceability, dark-store inventory, Razorpay payments, delivery slots, GST invoices. Concepts build on each other instead of resetting with every toy example.
    - **Four reader bands in every chapter.** Beginner → Intermediate → Senior → Architect, in the same chapter and on the same problem.
    - **L1 → L4 code ladder.** Each chapter solves one problem four times: plain Java, Spring, production-grade, enterprise-scale.
    - **Design documents and sequence diagrams.** These are included in every chapter, and every chapter shows a failure path alongside the happy path.
    - **20+ interview Q&As per chapter** with 30-second answers, deep answers, follow-ups and red flags.

## Structure

| Part | Focus | Band |
|---|---|---|
| **0 · Orientation** | How to read the book; what system design is | All |
| **I · Foundations** | Estimation, networking, APIs, databases, caching, load balancing, scalability | B1 |
| **II · Distributed Core** | Replication, sharding, consensus, transactions, messaging, microservices, resilience | B2 |
| **III · Production** | Data at scale, search, security, observability, performance, deployment, DR | B3 |
| **IV · Architect** | Decisions, design docs, DDD, modern styles, AI-era design, compliance, leadership | B4 |
| **V · Case Studies** | 19 classic and domain designs, from URL shortener to order matching and a GenAI assistant | All |
| **VI · Capstone** | The full grocery marketplace, end to end | B3–B4 |
| **VII · Interview** | Framework, level expectations, Q&A bank, mock interviews, behavioural & leadership | All |
| **Industry Designs** | Sector designs: Amazon-style marketplace, Flipkart-style sales, banking, healthcare and more | B2–B4 |
| **Appendix** | Full question index, glossary, lab catalogue | All |

Start with [Chapter 00 — How to Use This Handbook](00-orientation/00-how-to-use-this-handbook.md).

## At a glance

- **63 chapters** (00–62); every chapter from 01 has four labs whose outputs are quoted verbatim in the text.
- **1,600 interview questions** (1,240 in the chapters plus 360 in the industry designs), 500 marked ⭐ high-frequency.
- **248 labs**, catalogued in the [Lab Catalogue](99-appendix/lab-catalogue.md) and checked by CI with `labs/run-labs.py` (see the [labs README](https://github.com/khiruashish/system-design-playbook/tree/main/labs)); **189 terms** in the [Glossary](99-appendix/glossary.md).
- **Coding Interview tab:** 12 system-design data structures as runnable Java, each with a Spring Boot 4 production version ([start here](coding-interview/index.md)).

## Industry designs

- [Industry Designs tab](08-industry/index.md): end-to-end designs per sector, starting with the [Amazon-Style Global Marketplace](08-industry/i01-amazon-style-global-marketplace.md).
- New: [Ch 62 — GenAI Shopping Assistant](05-case-studies/62-genai-assistant-platform.md): RAG retrieval, LLM serving on GPUs, cost controls and safe agents.

## Quick links for interview preparation

- [⭐ Drill Bank](07-interview/qa-bank-starred.md): every high-frequency question with its 30-second answer.
- [Full Question Index](99-appendix/qa-index.md): all questions by part, chapter and level.
- [Ch 57 — Interview Framework](07-interview/57-the-system-design-interview-framework.md) · [Ch 60 — Mock Interviews](07-interview/60-mock-interviews.md) · [Ch 61 — Behavioural & Leadership](07-interview/61-behavioral-and-leadership.md)
