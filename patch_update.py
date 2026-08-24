with open('app/src/main/java/com/example/ui/components/CameraViewfinder.kt', 'r') as f:
    content = f.read()

target = """                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()"""

replacement = """                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            update = { _ ->
                cameraControl?.enableTorch(isFlashEnabled)
            },
            modifier = Modifier.fillMaxSize()"""

content = content.replace(target, replacement)

with open('app/src/main/java/com/example/ui/components/CameraViewfinder.kt', 'w') as f:
    f.write(content)
