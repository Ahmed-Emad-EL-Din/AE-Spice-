package com.example.engine

import java.io.File

data class WorkspaceTab(
    val id: String,
    val name: String,
    val file: File?,
    val components: List<Component>,
    val wires: List<Wire>
)
