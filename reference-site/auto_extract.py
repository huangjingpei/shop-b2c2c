import ctypes
import os
import time
import subprocess
import pyperclip
import pyautogui

def find_hostinger_window():
    desk = ctypes.windll.user32.OpenInputDesktop(0, False, 0x01FF)
    if desk:
        ctypes.windll.user32.SetThreadDesktop(desk)
    
    found = []
    def cb(hwnd, extra):
        if ctypes.windll.user32.IsWindowVisible(hwnd):
            l = ctypes.windll.user32.GetWindowTextLengthW(hwnd)
            if l > 0:
                buf = ctypes.create_unicode_buffer(l + 1)
                ctypes.windll.user32.GetWindowTextW(hwnd, buf, l + 1)
                t = buf.value
                if 'Hostinger' in t or 'AI Builder' in t:
                    found.append((hwnd, t))
        return True
    
    WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_int, ctypes.c_int)
    ctypes.windll.user32.EnumDesktopWindows(desk, WNDENUMPROC(cb), 0)
    return found

js_code = r'''
(async function extractHostingerCode() {
  console.log("🚀 [Auto-Extractor] 开始提取 Hostinger 代码...");
  const filesMap = {};

  // 1. Monaco Editor Models
  try {
    if (window.monaco && monaco.editor && monaco.editor.getModels) {
      for (const m of monaco.editor.getModels()) {
        const p = m.uri?.path || m.uri?.fsPath;
        if (p && !p.endsWith('.d.ts') && !p.startsWith('/node_modules')) {
          filesMap[p] = m.getValue();
        }
      }
    }
  } catch(e) {}

  // 2. React Fiber Store
  try {
    const queue = [];
    const visited = new Set();
    const allEls = document.querySelectorAll('*');
    for (const el of allEls) {
      for (const key in el) {
        if (key.startsWith('__reactFiber$')) queue.push(el[key]);
      }
    }
    while (queue.length > 0) {
      const fiber = queue.shift();
      if (!fiber || visited.has(fiber)) continue;
      visited.add(fiber);
      for (const t of [fiber.memoizedProps, fiber.memoizedState]) {
        if (!t || typeof t !== 'object') continue;
        const scan = (d) => {
          if (!d || typeof d !== 'object') return;
          let hits = 0;
          for (const k in d) {
            if (typeof k === 'string' && (k.endsWith('.tsx') || k.endsWith('.ts') || k.endsWith('.json') || k.endsWith('.css') || k.includes('package.json'))) hits++;
          }
          if (hits >= 2) {
            for (const [k, v] of Object.entries(d)) {
              const c = typeof v === 'string' ? v : (v && v.content ? v.content : (v && v.value ? v.value : null));
              if (c !== null) filesMap[k] = c;
            }
          }
        };
        scan(t);
        if (t.files) scan(t.files);
        if (t.fileMap) scan(t.fileMap);
        if (t.project?.files) scan(t.project.files);
        if (t.workspace?.files) scan(t.workspace.files);
      }
      if (fiber.child) queue.push(fiber.child);
      if (fiber.sibling) queue.push(fiber.sibling);
    }
  } catch(e) {}

  console.log("📊 内存初探发现文件数:", Object.keys(filesMap).length);

  // 3. Tree Walker
  const isFileNode = (text) => /\.(tsx?|jsx?|json|css|html|env|ico|svg|md)$/i.test(text.trim());
  const treeNodes = Array.from(document.querySelectorAll('*')).filter(el => {
    const text = el.innerText ? el.innerText.trim() : '';
    return isFileNode(text) && el.children.length === 0;
  });
  const seenTexts = new Set();
  const uniqueFiles = [];
  for (const node of treeNodes) {
    const text = node.innerText.trim();
    if (!seenTexts.has(text)) {
      seenTexts.add(text);
      uniqueFiles.push({ name: text, element: node });
    }
  }

  for (let i = 0; i < uniqueFiles.length; i++) {
    const item = uniqueFiles[i];
    try {
      item.element.click();
      await new Promise(r => setTimeout(r, 200));
      let content = '';
      if (window.monaco?.editor) {
        const active = monaco.editor.getModels().find(m => m.uri.path.includes(item.name));
        if (active) content = active.getValue();
        else if (monaco.editor.getEditors().length > 0) {
          content = monaco.editor.getEditors()[0].getValue();
        }
      }
      if (!content) {
        const viewLines = document.querySelector('.view-lines');
        if (viewLines) content = viewLines.innerText;
      }
      if (content) {
        const key = Object.keys(filesMap).find(k => k.endsWith(item.name)) || item.name;
        filesMap[key] = content;
      }
    } catch(e) {}
  }

  const count = Object.keys(filesMap).length;
  console.log("🎉 最终提取文件数:", count);
  if (count > 0) {
    const blob = new Blob([JSON.stringify(filesMap, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'hostinger-code.json';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    console.log("✅ hostinger-code.json 已触发下载！");
  }
})();
'''

def main():
    windows = find_hostinger_window()
    if not windows:
        print("Error: Could not find Hostinger window.")
        return

    hwnd, title = windows[0]
    print(f"Targeting window [{hwnd}]...")

    # Attach to desktop and focus window
    desk = ctypes.windll.user32.OpenInputDesktop(0, False, 0x01FF)
    if desk:
        ctypes.windll.user32.SetThreadDesktop(desk)
    
    ctypes.windll.user32.ShowWindow(hwnd, 9) # SW_RESTORE
    ctypes.windll.user32.SetForegroundWindow(hwnd)
    time.sleep(1.2)

    # 1. Type "允许粘贴" and hit Enter to unlock Chrome / Edge paste protection
    print("Step 1: Unlocking paste protection in DevTools...")
    pyperclip.copy("允许粘贴")
    pyautogui.hotkey('ctrl', 'v')
    time.sleep(0.5)
    pyautogui.press('enter')
    time.sleep(1.0)

    # 2. Paste JS extraction code and hit Enter
    print("Step 2: Injecting JS extractor script...")
    pyperclip.copy(js_code)
    pyautogui.hotkey('ctrl', 'v')
    time.sleep(0.5)
    pyautogui.press('enter')
    print("Script injected! Waiting for download to complete...")

    # 3. Wait for hostinger-code.json in Downloads
    downloads_path = os.path.expanduser('~\\Downloads\\hostinger-code.json')
    # If old one exists, delete it first so we know a fresh one downloaded
    if os.path.exists(downloads_path):
        try:
            os.remove(downloads_path)
        except:
            pass

    success = False
    for i in range(45):
        time.sleep(1.0)
        if os.path.exists(downloads_path) and os.path.getsize(downloads_path) > 100:
            print(f"Found downloaded file: {downloads_path} ({os.path.getsize(downloads_path)} bytes)!")
            success = True
            break
        print(f"Waiting... ({i+1}/45s)")

    if success:
        print("\nStep 3: Unpacking files to reference-site...")
        cmd = ['node', 'E:\\shop-b2b2c\\reference-site\\unpack.js']
        subprocess.run(cmd, check=True)
        print("\nALL DONE! Files successfully extracted into E:\\shop-b2b2c\\reference-site\\extracted")
    else:
        print("\nTimeout waiting for download. Please check if the browser prompted for download permissions or blocked popups.")

if __name__ == '__main__':
    main()
