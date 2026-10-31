import ctypes
import os
import time
import subprocess
import pyperclip
import pyautogui

def get_hostinger_hwnd():
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
                    found.append(hwnd)
        return True
    
    WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_int, ctypes.c_int)
    ctypes.windll.user32.EnumDesktopWindows(desk, WNDENUMPROC(cb), 0)
    return found[0] if found else None

def send_unicode_char(ch):
    # Send unicode character using SendInput
    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [
            ("wVk", ctypes.c_ushort),
            ("wScan", ctypes.c_ushort),
            ("dwFlags", ctypes.c_ulong),
            ("time", ctypes.c_ulong),
            ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong))
        ]
    class INPUT(ctypes.Structure):
        class _INPUT(ctypes.Union):
            _fields_ = [("ki", KEYBDINPUT)]
        _anonymous_ = ("_input",)
        _fields_ = [("type", ctypes.c_ulong), ("_input", _INPUT)]
    
    KEYEVENTF_UNICODE = 0x0004
    KEYEVENTF_KEYUP = 0x0002
    
    inp_down = INPUT()
    inp_down.type = 1
    inp_down.ki.wScan = ord(ch)
    inp_down.ki.dwFlags = KEYEVENTF_UNICODE
    
    inp_up = INPUT()
    inp_up.type = 1
    inp_up.ki.wScan = ord(ch)
    inp_up.ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP
    
    ctypes.windll.user32.SendInput(1, ctypes.byref(inp_down), ctypes.sizeof(INPUT))
    time.sleep(0.02)
    ctypes.windll.user32.SendInput(1, ctypes.byref(inp_up), ctypes.sizeof(INPUT))
    time.sleep(0.02)

def type_text(text):
    for ch in text:
        send_unicode_char(ch)

js_extractor = r'''
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
    hwnd = get_hostinger_hwnd()
    if not hwnd:
        print("Could not find Hostinger window.")
        return
    
    print(f"Targeting window [{hwnd}]...")
    
    desk = ctypes.windll.user32.OpenInputDesktop(0, False, 0x01FF)
    if desk:
        ctypes.windll.user32.SetThreadDesktop(desk)
    
    # Ensure window is foreground
    curr_th = ctypes.windll.kernel32.GetCurrentThreadId()
    fore = ctypes.windll.user32.GetForegroundWindow()
    fore_th = ctypes.windll.user32.GetWindowThreadProcessId(fore, None)
    target_th = ctypes.windll.user32.GetWindowThreadProcessId(hwnd, None)
    
    ctypes.windll.user32.AttachThreadInput(curr_th, fore_th, True)
    ctypes.windll.user32.AttachThreadInput(curr_th, target_th, True)
    ctypes.windll.user32.ShowWindow(hwnd, 9)
    ctypes.windll.user32.SetForegroundWindow(hwnd)
    ctypes.windll.user32.AttachThreadInput(curr_th, fore_th, False)
    ctypes.windll.user32.AttachThreadInput(curr_th, target_th, False)
    time.sleep(0.8)

    # Focus DevTools Console: Ctrl + Shift + J
    print("Pressing Ctrl+Shift+J to focus console...")
    pyautogui.hotkey('ctrl', 'shift', 'j')
    time.sleep(1.0)

    # Type "allow pasting"
    print("Typing 'allow pasting'...")
    pyautogui.write('allow pasting', interval=0.03)
    pyautogui.press('enter')
    time.sleep(0.5)

    # Also type "允许粘贴"
    print("Typing '允许粘贴'...")
    type_text("允许粘贴")
    pyautogui.press('enter')
    time.sleep(0.5)

    # Now paste JS code
    print("Pasting extraction script...")
    pyperclip.copy(js_extractor)
    pyautogui.hotkey('ctrl', 'v')
    time.sleep(0.5)
    pyautogui.press('enter')
    print("Script dispatched! Monitoring Downloads folder...")

    downloads_path = os.path.expanduser('~\\Downloads\\hostinger-code.json')
    if os.path.exists(downloads_path):
        try: os.remove(downloads_path)
        except: pass

    for i in range(40):
        time.sleep(1.0)
        if os.path.exists(downloads_path) and os.path.getsize(downloads_path) > 100:
            print(f"SUCCESS! Downloaded file found: {downloads_path} ({os.path.getsize(downloads_path)} bytes)")
            subprocess.run(['node', 'E:\\shop-b2b2c\\reference-site\\unpack.js'], check=True)
            return

    print("Download did not start in 40s.")

if __name__ == '__main__':
    main()
