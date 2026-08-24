with open('app/src/main/java/com/example/ui/CameraGuideScreen.kt', 'r') as f:
    content = f.read()

import re
content = re.sub(r'(@OptIn\(ExperimentalPermissionsApi::class\)\s*@Composable\s*){2,}', r'@OptIn(ExperimentalPermissionsApi::class)\n@Composable\n', content)

with open('app/src/main/java/com/example/ui/CameraGuideScreen.kt', 'w') as f:
    f.write(content)
