# Matrix — Design Document
**Version:** 0.0.1  
**Date:** 2026-07-05  

## Overview
This is a maven multi-module project which are mostly implemented by Spring Boot.

## Project Structure
```
matrix/
├── operator/       # Spring AI Agent Operator
├── parent/         # Parent POM for Java library version management across all modules.
├── scripts/        # Handy scripts
├── docs/           # Design documentation and guidelines
└── pom.xml         # Root POM including all maven modules
```

## Modules Description

### matrix/operator (Java)
The core Spring AI Agent CLI application. This module is controlled and should not be modified manually.

### matrix/parent (POM)
Parent POM that manages common dependencies and plugin versions for all Java modules in the project. All shared libraries should be defined here.

### matrix/scripts (Shell Script)
Utility scripts for build automation, testing, and development tasks.


