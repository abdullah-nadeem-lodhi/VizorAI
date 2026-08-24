import re

with open('app/src/main/java/com/example/ui/components/CameraViewfinder.kt', 'r') as f:
    content = f.read()

# Add mutableStateOf, getValue, setValue imports if needed
if 'import androidx.compose.runtime.mutableStateOf' not in content:
    content = content.replace('import androidx.compose.runtime.remember', 'import androidx.compose.runtime.remember\nimport androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.getValue\nimport androidx.compose.runtime.setValue')

# Add cameraControl state
if 'var cameraControl' not in content:
    content = content.replace('val cameraExecutor = remember { Executors.newSingleThreadExecutor() }', 
        'val cameraExecutor = remember { Executors.newSingleThreadExecutor() }\n    var cameraControl: androidx.camera.core.CameraControl? by remember { mutableStateOf(null) }')

# Update try block
try_block_old = """                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    }"""

try_block_new = """                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                        val hasFlash = camera.cameraInfo.hasFlashUnit()
                        onFlashSupportChanged(hasFlash)
                        cameraControl = camera.cameraControl
                    }"""
content = content.replace(try_block_old, try_block_new)

# Add update block
update_block_old = """                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()"""

update_block_new = """                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            update = { _ ->
                cameraControl?.enableTorch(isFlashEnabled)
            },
            modifier = Modifier.fillMaxSize()"""
content = content.replace(update_block_old, update_block_new)

with open('app/src/main/java/com/example/ui/components/CameraViewfinder.kt', 'w') as f:
    f.write(content)
