import ctypes
import time
import pyperclip
import pyautogui

def find_target_window():
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

print("Scanning for Hostinger window...")
windows = find_target_window()
for h, t in windows:
    print(f"Found: {h} - {t}")
