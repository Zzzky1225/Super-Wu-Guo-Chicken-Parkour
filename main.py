# -*- coding: utf-8 -*-
"""超级吴国鸡跑酷 —— 单文件版。

结构：
  1. 资源加载        图片/音频/字体，全部按需缓存
  2. 渲染层          GLSL 后端、玻璃材质、模糊、坐标换算
  3. 全局状态        设置项、存档、成绩
  4. 物理与逻辑      跳跃、碰撞、金币、障碍
  5. 事件处理        每个界面一个 handler
  6. 主循环          逻辑 → 渲染 → 呈现
渲染后端：gl（GLSL 直连）/ gpu（SDL 纹理降级）/ soft（纯软件），逐级回退。
"""
import ctypes
import io
import pygame
import pygame._sdl2.video
import os
import random
import sys
import math
import time
import json
import hashlib
import subprocess
# ---------- 1. 资源加载 ----------
def _resource_dirs():
    """资源根目录候选：PyInstaller 解包目录 > exe 所在目录 > 当前工作目录 > 源码目录。
    exe 从快捷方式、别的目录启动时也能找到 assets。"""
    dirs = []
    if hasattr(sys, "_MEIPASS"): dirs.append(sys._MEIPASS)
    if getattr(sys, "frozen", False): dirs.append(os.path.dirname(os.path.abspath(sys.executable)))
    dirs.append(os.path.abspath("."))
    try: dirs.append(os.path.dirname(os.path.abspath(__file__)))
    except Exception: pass
    return dirs

def get_asset_path(file_name):
    dirs = _resource_dirs()
    for d in dirs:
        p = os.path.join(d, "assets", file_name)
        if os.path.exists(p): return p
    return os.path.join(dirs[0], "assets", file_name)   # 都没有就交给上层的 try/except

def _cv(img):
    # GPU 模式没有 display surface，convert_alpha/convert 会抛错；能转才转
    return img.convert_alpha() if pygame.display.get_surface() is not None else img

def load_img(filename, w=None, h=None):
    try:
        img = _cv(pygame.image.load(get_asset_path(filename)))
        return pygame.transform.scale(img, (w, h)) if w and h else img
    except Exception: return None
def load_img_scale_height(filename, target_h):
    try:
        img = _cv(pygame.image.load(get_asset_path(filename)))
        ow, oh = img.get_size()
        return pygame.transform.scale(img, (int(ow * (target_h / oh)), target_h))
    except Exception: return None
def load_snd(filename):
    try:
        snd = pygame.mixer.Sound(get_asset_path(filename))
        snd.set_volume(1)
        return snd
    except Exception: return None
_WIN_FONT_FILES = ("msyh.ttc", "msyhbd.ttc", "simhei.ttf", "simsun.ttc")
def get_chinese_font(size=36):
    # 1) 包内字体 2) 系统字体文件（直接读文件，避开 pygame SysFont 枚举字体目录的老毛病）
    # 3) pygame 内置字体兜底：中文会缺字，但至少不抛异常把游戏掐死
    p = get_asset_path("NotoSansSC.ttf")
    if os.path.exists(p):
        try: return pygame.font.Font(p, size)
        except Exception: pass
    fontdir = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")
    for name in _WIN_FONT_FILES:
        try:
            fp = os.path.join(fontdir, name)
            if os.path.exists(fp): return pygame.font.Font(fp, size)
        except Exception: pass
    try: return pygame.font.SysFont("Microsoft YaHei", size)
    except Exception: return pygame.font.Font(None, size)

# ---------- 窗口缩放映射 ----------
DESIGN_W, DESIGN_H = 1920, 1017
# 映射缓存，仅初始化 / VIDEORESIZE 更新，主循环只读
scale = 1.0
off_x = 0.0
off_y = 0.0
draw_w = DESIGN_W
draw_h = DESIGN_H

def update_mapping(win_w, win_h):
    global scale, off_x, off_y, draw_w, draw_h
    scale = min(win_w / DESIGN_W, win_h / DESIGN_H)
    dst_w = DESIGN_W * scale
    dst_h = DESIGN_H * scale
    off_x = (win_w - dst_w) / 2
    off_y = (win_h - dst_h) / 2
    draw_w, draw_h = int(dst_w), int(dst_h)

# 高分屏：声明 per-monitor DPI 感知。打包成 exe 后没有 python.exe 的 manifest，
# 不声明的话 Windows 会按系统缩放把整个窗口拉伸，画面变模糊、也不在设计分辨率上 1:1。
def _enable_dpi_awareness():
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
        return
    except Exception:
        pass
    try: ctypes.windll.user32.SetProcessDPIAware()
    except Exception: pass
_enable_dpi_awareness()

# 初始化
pygame.init()

# ---------- 渲染后端选择（命令行 > render.cfg > 默认 gl） ----------
def _render_cfg_path():
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    d = os.path.join(base, "SuperWuGuoChicken")
    try:
        os.makedirs(d, exist_ok=True)
        return os.path.join(d, "render.cfg")
    except Exception:
        return None
RENDER_CFG = _render_cfg_path()
def load_render_pref():
    if RENDER_CFG is None: return None
    try:
        with open(RENDER_CFG, "r", encoding="utf-8") as fh:
            v = fh.read().strip().lower()
        return v if v in ("gl", "soft") else None
    except Exception:
        return None
def save_render_pref(key):
    if RENDER_CFG is None: return False
    try:
        with open(RENDER_CFG, "w", encoding="utf-8") as fh: fh.write(key)
        return True
    except Exception:
        return False
RENDER_BACKEND = (os.environ.get("WUGUO_RENDER") or load_render_pref() or "gl").strip().lower()
GPU_OK = False
gpu_win = None
gpu_renderer = None

# ---------- 内置 GLSL 渲染后端 ----------
# 直接调 OpenGL 4.x，把 SDL_Renderer 的语义（target / blit / fill_rect / present）
# 换成 GL，外面的绘制代码一行都不用改。模糊是真两遍可分离高斯：31 抽、1:1 采样、零缩放。
# 每个 GL 状态（程序/纹理/FBO/混合模式/视口）都带缓存，重复值不再下发。
try:
    import OpenGL
    OpenGL.ERROR_CHECKING = False      # 关掉逐调用错误轮询，它会把上一个的错误报在下一个调用上
    from OpenGL import GL as gl
    from OpenGL.raw.GL.VERSION.GL_1_1 import (glBindTexture as _raw_bindtex,
                                              glDrawArrays as _raw_draw,
                                              glViewport as _raw_viewport,
                                              glScissor as _raw_scissor)
    from OpenGL.raw.GL.VERSION.GL_1_5 import glBufferSubData as _raw_bufsub
    from OpenGL.raw.GL.VERSION.GL_2_0 import (glUseProgram as _raw_use,
                                              glUniform1i as _raw_u1i,
                                              glUniform2f as _raw_u2f,
                                              glUniform4f as _raw_u4f)
    GL_AVAILABLE = True
except Exception as _e:
    gl, GL_AVAILABLE = None, False
    print("[GL] PyOpenGL 不可用: %r" % (_e,))

VS_QUAD = """
#version 330 core
layout(location=0) in vec2 a_pos;
layout(location=1) in vec2 a_uv;
uniform vec2 u_res;
out vec2 v_uv;
void main(){
    gl_Position = vec4(a_pos.x / u_res.x * 2.0 - 1.0, 1.0 - a_pos.y / u_res.y * 2.0, 0.0, 1.0);
    v_uv = a_uv;
}
"""
FS_TEX = """
#version 330 core
in vec2 v_uv;
out vec4 o_col;
uniform sampler2D u_tex;
uniform vec4 u_col;
void main(){ o_col = texture(u_tex, v_uv) * u_col; }
"""
FS_SOLID = """
#version 330 core
in vec2 v_uv;
out vec4 o_col;
uniform vec4 u_col;
void main(){ o_col = u_col; }
"""
FS_BLUR = """
#version 330 core
in vec2 v_uv;
out vec4 o_col;
uniform sampler2D u_tex;
uniform vec2 u_src_off;
uniform vec2 u_src_scale;
uniform vec2 u_step;
uniform float u_radius;
void main(){
    vec4 acc = vec4(0.0);
    float wsum = 0.0;
    for (int i = -15; i <= 15; ++i) {
        float t = float(i) / 5.0;
        float w = exp(-0.5 * t * t);
        vec2 uv = u_src_off + v_uv * u_src_scale + u_step * (float(i) * u_radius / 15.0);
        acc += texture(u_tex, uv) * w;
        wsum += w;
    }
    o_col = acc / wsum;
}
"""

def _compile(src, kind):
    sh = gl.glCreateShader(kind)
    gl.glShaderSource(sh, src)
    gl.glCompileShader(sh)
    if not gl.glGetShaderiv(sh, gl.GL_COMPILE_STATUS):
        raise RuntimeError("shader 编译失败:\n" + (gl.glGetShaderInfoLog(sh) or b"").decode(errors="ignore"))
    return sh

def _link(fs_src, uniforms=()):
    prog = int(gl.glCreateProgram())
    vs = _compile(VS_QUAD, gl.GL_VERTEX_SHADER)
    fs = _compile(fs_src, gl.GL_FRAGMENT_SHADER)
    gl.glAttachShader(prog, vs)
    gl.glAttachShader(prog, fs)
    gl.glLinkProgram(prog)
    if not gl.glGetProgramiv(prog, gl.GL_LINK_STATUS):
        raise RuntimeError("shader 链接失败:\n" + (gl.glGetProgramInfoLog(prog) or b"").decode(errors="ignore"))
    gl.glDetachShader(prog, vs)
    gl.glDetachShader(prog, fs)
    gl.glDeleteShader(vs)
    gl.glDeleteShader(fs)
    return prog, {n: gl.glGetUniformLocation(prog, n) for n in uniforms}

class GLTexture:
    """和 pygame 的 Texture 同名同语义，内部是一个 GL 纹理（可选带 FBO）"""

    def __init__(self, renderer, size, target=False, streaming=False, scale_quality=1):
        self.renderer = renderer
        self.width = max(1, int(size[0]))
        self.height = max(1, int(size[1]))
        self._tex = int(gl.glGenTextures(1))
        gl.glBindTexture(gl.GL_TEXTURE_2D, self._tex)
        gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGBA8, self.width, self.height, 0,
                        gl.GL_RGBA, gl.GL_UNSIGNED_BYTE, None)
        gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_LINEAR)
        gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_LINEAR)
        gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_WRAP_S, gl.GL_CLAMP_TO_EDGE)
        gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_WRAP_T, gl.GL_CLAMP_TO_EDGE)
        renderer._cur["tex"] = None        # 刚绑过纹理，缓存作废
        self.fbo = None
        self.flip_v = bool(target)         # FBO 内容在显存里是倒序的，采样/回读要翻
        if target:
            self.fbo = int(gl.glGenFramebuffers(1))
            gl.glBindFramebuffer(gl.GL_FRAMEBUFFER, self.fbo)
            gl.glFramebufferTexture2D(gl.GL_FRAMEBUFFER, gl.GL_COLOR_ATTACHMENT0,
                                      gl.GL_TEXTURE_2D, self._tex, 0)
            ok = gl.glCheckFramebufferStatus(gl.GL_FRAMEBUFFER)
            gl.glBindFramebuffer(gl.GL_FRAMEBUFFER, 0)
            renderer._cur["fbo"] = None
            if ok != gl.GL_FRAMEBUFFER_COMPLETE:
                raise RuntimeError("FBO 建立失败: %x" % ok)

    @property
    def size(self):
        return (self.width, self.height)

    @classmethod
    def from_surface(cls, renderer, surf):
        t = cls(renderer, surf.get_size())
        t.update(surf)
        return t

    def update(self, surf, area=None):
        if surf is None:
            return
        if surf.get_size() != (self.width, self.height) and area is None:
            surf = pygame.transform.smoothscale(surf, (self.width, self.height))
        data = pygame.image.tobytes(surf, "RGBA")
        buf = (ctypes.c_char * len(data)).from_buffer_copy(data)
        gl.glBindTexture(gl.GL_TEXTURE_2D, self._tex)
        self.renderer._cur["tex"] = None
        if area is None:
            gl.glTexSubImage2D(gl.GL_TEXTURE_2D, 0, 0, 0, self.width, self.height,
                               gl.GL_RGBA, gl.GL_UNSIGNED_BYTE, buf)
        else:
            # area 是左上角原点的屏幕坐标；GL 纹理自下而上存，y 要翻过来
            a = pygame.Rect(area)
            data = pygame.image.tobytes(surf.subsurface(a), "RGBA")
            buf = (ctypes.c_char * len(data)).from_buffer_copy(data)
            gl.glTexSubImage2D(gl.GL_TEXTURE_2D, 0, a.x, self.height - (a.y + a.h), a.w, a.h,
                               gl.GL_RGBA, gl.GL_UNSIGNED_BYTE, buf)

    def destroy(self):
        try:
            if self.fbo:
                gl.glDeleteFramebuffers(1, [self.fbo])
                self.fbo = None
            gl.glDeleteTextures([self._tex])
            self.renderer._cur["tex"] = None
        except Exception:
            pass

class GLRenderer:
    def __init__(self, window):
        self.win = window
        self._target = None
        self._col = (255, 255, 255, 255)
        self._blend = pygame.BLENDMODE_BLEND
        gl.glDisable(gl.GL_DEPTH_TEST)
        gl.glDisable(gl.GL_CULL_FACE)
        gl.glEnable(gl.GL_BLEND)
        gl.glEnable(gl.GL_SCISSOR_TEST)
        gl.glPixelStorei(gl.GL_UNPACK_ALIGNMENT, 1)
        gl.glPixelStorei(gl.GL_PACK_ALIGNMENT, 1)

        self.tex_prog, self._uv = _link(FS_TEX, ("u_tex", "u_col", "u_res"))
        self.solid_prog, self._us = _link(FS_SOLID, ("u_col", "u_res"))
        self.blur_prog, self._ub = _link(FS_BLUR, ("u_tex", "u_src_off", "u_src_scale",
                                                   "u_step", "u_radius", "u_res"))

        # 静态 uniform：采样器固定 0 号贴图，blit 不做颜色调制（draw_color 只管 fill_rect）
        _raw_use(self.tex_prog)
        _raw_u1i(self._uv["u_tex"], 0)
        _raw_u4f(self._uv["u_col"], 1.0, 1.0, 1.0, 1.0)
        _raw_use(self.blur_prog)
        _raw_u1i(self._ub["u_tex"], 0)

        self._cur = {"fbo": None, "prog": None, "tex": None, "blend": None, "res": None}

        self.vao = int(gl.glGenVertexArrays(1))
        self.vbo = int(gl.glGenBuffers(1))
        gl.glBindVertexArray(self.vao)
        gl.glBindBuffer(gl.GL_ARRAY_BUFFER, self.vbo)
        gl.glBufferData(gl.GL_ARRAY_BUFFER, 24 * 4, None, gl.GL_STREAM_DRAW)
        gl.glEnableVertexAttribArray(0)
        gl.glVertexAttribPointer(0, 2, gl.GL_FLOAT, gl.GL_FALSE, 16, ctypes.c_void_p(0))
        gl.glEnableVertexAttribArray(1)
        gl.glVertexAttribPointer(1, 2, gl.GL_FLOAT, gl.GL_FALSE, 16, ctypes.c_void_p(8))
        self._vao_bound = True
        self._pool = {}
        # 热路径的 GL 入口：用 raw 版本 + 预建指针，单次调用省掉 PyOpenGL 的参数转换
        self._bufsub = _raw_bufsub
        self._drawarr = _raw_draw
        self._bindvao = gl.glBindVertexArray
        self._use = _raw_use
        self._bindtex = _raw_bindtex
        self._bindfbo = gl.glBindFramebuffer
        self._viewport = _raw_viewport
        self._scissor = _raw_scissor
        self._u4f = _raw_u4f
        self._u2f = _raw_u2f
        self._verts = (ctypes.c_float * 24)()
        self._vptr = ctypes.cast(self._verts, ctypes.c_void_p)

    @property
    def target(self):
        return self._target

    @target.setter
    def target(self, tex):
        self._target = tex

    @property
    def draw_color(self):
        return self._col

    @draw_color.setter
    def draw_color(self, col):
        self._col = (col[0], col[1], col[2], col[3] if len(col) > 3 else 255)

    @property
    def draw_blend_mode(self):
        return self._blend

    @draw_blend_mode.setter
    def draw_blend_mode(self, mode):
        self._blend = mode

    def _bind_target(self):
        # 渲染目标（None=窗口），顺带恢复视口与裁剪框；模糊 pass 会临时关掉 scissor
        tex = self._target
        cur = self._cur
        if tex is None:
            size = self.win.size
            if cur["fbo"] != size:
                self._bindfbo(gl.GL_FRAMEBUFFER, 0)
                self._viewport(0, 0, size[0], size[1])
                self._scissor(0, 0, size[0], size[1])
                cur["fbo"] = size
            sc = size
        else:
            if cur["fbo"] != tex.fbo:
                self._bindfbo(gl.GL_FRAMEBUFFER, tex.fbo)
                self._viewport(0, 0, tex.width, tex.height)
                self._scissor(0, 0, tex.width, tex.height)
                cur["fbo"] = tex.fbo
            sc = (tex.width, tex.height)
        gl.glEnable(gl.GL_SCISSOR_TEST)
        return sc

    def _apply_blend(self):
        b = self._blend
        if b == self._cur["blend"]:
            return
        self._cur["blend"] = b
        if b == pygame.BLENDMODE_NONE:
            gl.glDisable(gl.GL_BLEND)
            return
        gl.glEnable(gl.GL_BLEND)
        if b == pygame.BLENDMODE_ADD:
            gl.glBlendFuncSeparate(gl.GL_SRC_ALPHA, gl.GL_ONE, gl.GL_ONE, gl.GL_ONE)
        elif b in (pygame.BLENDMODE_MOD, getattr(pygame, "BLENDMODE_MUL", 4)):
            gl.glBlendFuncSeparate(gl.GL_ZERO, gl.GL_SRC_COLOR, gl.GL_ZERO, gl.GL_SRC_ALPHA)
        else:
            gl.glBlendFuncSeparate(gl.GL_SRC_ALPHA, gl.GL_ONE_MINUS_SRC_ALPHA,
                                   gl.GL_ONE, gl.GL_ONE_MINUS_SRC_ALPHA)

    def _quad(self, x, y, w, h, u0, v0, u1, v1):
        x2, y2 = x + w, y + h
        self._verts[:] = (x, y, u0, v0, x2, y, u1, v0, x, y2, u0, v1,
                          x2, y, u1, v0, x2, y2, u1, v1, x, y2, u0, v1)
        if not self._vao_bound:
            self._bindvao(self.vao)
            gl.glBindBuffer(gl.GL_ARRAY_BUFFER, self.vbo)
            self._vao_bound = True
        self._bufsub(gl.GL_ARRAY_BUFFER, 0, 24 * 4, self._vptr)   # 覆写预分配缓冲，不重新分配
        self._drawarr(gl.GL_TRIANGLES, 0, 6)

    def clear(self):
        res = self._bind_target()
        col = self._col
        gl.glClearColor(col[0] / 255.0, col[1] / 255.0, col[2] / 255.0, col[3] / 255.0)
        gl.glClear(gl.GL_COLOR_BUFFER_BIT)
        return res

    def blit(self, tex, rect, area=None):
        if tex is None:
            return
        if not isinstance(rect, pygame.Rect):
            rect = pygame.Rect(rect)
        rw, rh = rect.w, rect.h
        if rw <= 0 or rh <= 0:
            return
        res = self._bind_target()
        self._apply_blend()
        tw, th = tex.width, tex.height
        if area is None:
            if tex.flip_v:
                u0, v0, u1, v1 = 0.0, 1.0, 1.0, 0.0
            else:
                u0, v0, u1, v1 = 0.0, 0.0, 1.0, 1.0
        else:
            a = area if isinstance(area, pygame.Rect) else pygame.Rect(area)
            u0 = a.x / tw
            u1 = (a.x + a.w) / tw
            if tex.flip_v:
                v0 = (th - a.y) / th
                v1 = (th - (a.y + a.h)) / th
            else:
                v0 = a.y / th
                v1 = (a.y + a.h) / th
        if self._cur["prog"] != self.tex_prog:
            self._use(self.tex_prog)
            self._cur["prog"] = self.tex_prog
            self._cur["res"] = None      # u_res 是按 program 存的，换程序必须重设，否则新程序里是 0
        tex_id = tex._tex
        if self._cur["tex"] != tex_id:
            self._bindtex(gl.GL_TEXTURE_2D, tex_id)
            self._cur["tex"] = tex_id
        key = (res[0], res[1])
        if self._cur["res"] != key:
            self._u2f(self._uv["u_res"], float(res[0]), float(res[1]))
            self._cur["res"] = key
        self._quad(rect.x, rect.y, rw, rh, u0, v0, u1, v1)

    def fill_rect(self, rect):
        if not isinstance(rect, pygame.Rect):
            rect = pygame.Rect(rect)
        if rect.w <= 0 or rect.h <= 0:
            return
        res = self._bind_target()
        self._apply_blend()
        col = self._col
        if self._cur["prog"] != self.solid_prog:
            self._use(self.solid_prog)
            self._cur["prog"] = self.solid_prog
            self._cur["res"] = None      # 同上：实心程序自己的 u_res 要重新喂一遍
        self._u4f(self._us["u_col"], col[0] / 255.0, col[1] / 255.0,
                  col[2] / 255.0, col[3] / 255.0)
        key = (res[0], res[1])
        if self._cur["res"] != key:
            self._u2f(self._us["u_res"], float(res[0]), float(res[1]))
            self._cur["res"] = key
        self._quad(rect.x, rect.y, rect.w, rect.h, 0, 0, 0, 0)

    def _pool_tex(self, w, h, idx):
        key = (w, h, idx)
        t = self._pool.get(key)
        if t is None:
            if len(self._pool) > 24:
                for v in self._pool.values():
                    v.destroy()
                self._pool.clear()
            t = GLTexture(self, (w, h), target=True)
            self._pool[key] = t
        return t

    def _invalidate(self):
        cur = self._cur
        cur["fbo"] = cur["prog"] = cur["tex"] = cur["blend"] = cur["res"] = None

    def gauss_blur(self, src_tex, src_rect, w, h, radius, dst_tex=None):
        """把 src_tex 的 src_rect 区域做两遍高斯，输出 w×h 纹理；radius 是源像素半径，1:1 不缩放"""
        w = max(1, int(w))
        h = max(1, int(h))
        radius = float(radius)
        if dst_tex is None:
            dst_tex = self._pool_tex(w, h, 2)
        tmp = self._pool_tex(w, h, 0)
        src_rect = pygame.Rect(src_rect)
        if src_tex.flip_v:
            # FBO 源：内容自下而上存，屏幕行 y 对应 v = 1 - y/H
            off = (src_rect.x / src_tex.width, (src_tex.height - (src_rect.y + src_rect.h)) / src_tex.height)
            scl = (src_rect.w / src_tex.width, src_rect.h / src_tex.height)
        else:
            # 上传纹理是正向存储；纵向那一遍会把画面翻一次，这里先自身上下翻过来读，两次抵消
            off = (src_rect.x / src_tex.width, (src_rect.y + src_rect.h) / src_tex.height)
            scl = (src_rect.w / src_tex.width, -src_rect.h / src_tex.height)

        self._invalidate()
        gl.glActiveTexture(gl.GL_TEXTURE0)
        gl.glDisable(gl.GL_SCISSOR_TEST)
        gl.glDisable(gl.GL_BLEND)
        self._cur["blend"] = pygame.BLENDMODE_NONE
        self._use(self.blur_prog)
        self._cur["prog"] = self.blur_prog
        ub = self._ub
        gl.glUniform2f(ub["u_src_off"], off[0], off[1])
        gl.glUniform2f(ub["u_src_scale"], scl[0], scl[1])
        gl.glUniform1f(ub["u_radius"], radius)
        gl.glUniform2f(ub["u_res"], float(w), float(h))
        self._cur["res"] = (w, h)

        # 第一遍横向：步长是一个源像素在源纹理 UV 里的长度
        self._bindfbo(gl.GL_FRAMEBUFFER, tmp.fbo)
        self._viewport(0, 0, w, h)
        self._bindtex(gl.GL_TEXTURE_2D, src_tex._tex)
        gl.glUniform2f(ub["u_step"], 1.0 / src_tex.width, 0.0)
        self._quad(0, 0, w, h, 0.0, 0.0, 1.0, 1.0)

        # 第二遍纵向：临时纹理只有 w×h，必须按它自己的 UV 全幅采样，否则局部区域会采到边角
        self._bindfbo(gl.GL_FRAMEBUFFER, dst_tex.fbo)
        self._viewport(0, 0, w, h)
        self._bindtex(gl.GL_TEXTURE_2D, tmp._tex)
        gl.glUniform2f(ub["u_src_off"], 0.0, 0.0)
        gl.glUniform2f(ub["u_src_scale"], 1.0, 1.0)
        gl.glUniform2f(ub["u_step"], 0.0, 1.0 / h)
        self._quad(0, 0, w, h, 0.0, 0.0, 1.0, 1.0)

        # 模糊只是借显卡一用，调用方的 target / 混合模式 / 裁剪框要还回去
        self._invalidate()
        self._bind_target()
        self._apply_blend()
        return dst_tex

    def present(self):
        self.win.flip()

    def to_surface(self):
        w, h = self.win.size
        self._bindfbo(gl.GL_FRAMEBUFFER, 0)
        gl.glReadBuffer(gl.GL_FRONT)
        buf = ctypes.create_string_buffer(w * h * 4)
        gl.glReadPixels(0, 0, w, h, gl.GL_RGBA, gl.GL_UNSIGNED_BYTE, buf)
        gl.glReadBuffer(gl.GL_BACK)
        self._invalidate()
        return pygame.image.frombytes(buf.raw, (w, h), "RGBA", True)

    def info(self):
        return {
            "gl_version": gl.glGetString(gl.GL_VERSION).decode(errors="ignore"),
            "renderer": gl.glGetString(gl.GL_RENDERER).decode(errors="ignore"),
            "vendor": gl.glGetString(gl.GL_VENDOR).decode(errors="ignore"),
        }

class GpuBlur:
    """整屏模糊底图（一次生成，页面静止期间零开销）"""
    def __init__(self, tex, w, h):
        self.tex, self.w, self.h = tex, w, h

class GpuFrost:
    """按钮的实时磨砂结果（GPU 降采样再放大，每帧一次）"""
    def __init__(self, tex, w, h):
        self.tex, self.w, self.h = tex, w, h

def _c4(color):
    return (color[0], color[1], color[2], color[3] if len(color) > 3 else 255)

class GpuTarget:
    """把 Surface 绘制语义搬到 SDL2 纹理上：整帧不产生一次上传"""
    def __init__(self):
        self._tex = {}
        self._order = []
    def get_size(self): return tuple(gpu_win.size)
    def get_width(self): return gpu_win.size[0]
    def get_height(self): return gpu_win.size[1]
    def get_rect(self): return pygame.Rect(0, 0, gpu_win.size[0], gpu_win.size[1])
    def tex(self, surf):
        # 键里带 id 但必须同时抓住 surf 本体：Surface 被回收后 id 会被复用，
        # 只按 id 缓存会把「中」错画成「轻」这种别人的旧纹理
        key = (id(surf), surf.get_width(), surf.get_height())
        ent = self._tex.get(key)
        if ent is None or ent[1] is not surf:
            ent = (tex_from_surface(surf), surf)
            self._tex[key] = ent
            self._order.append(key)
            if len(self._order) > 500:
                self._tex.pop(self._order.pop(0), None)
        return ent[0]
    def fill(self, color):
        gpu_renderer.draw_color = _c4(color)
        gpu_renderer.clear()
    def blit(self, src, dest, area=None):
        if isinstance(src, GpuFrost):
            rect = pygame.Rect(int(dest[0]), int(dest[1]), src.w, src.h)
            gpu_renderer.blit(src.tex, rect)
            return rect
        if isinstance(src, GpuBlur):
            w, h = (area[2], area[3]) if area is not None else (src.w, src.h)
            rect = pygame.Rect(int(dest[0]), int(dest[1]), w, h)
            gpu_renderer.blit(src.tex, rect, area)
            return rect
        if area is not None:
            w, h = area[2], area[3]
        else:
            w, h = src.get_size()
        if w <= 0 or h <= 0:
            return pygame.Rect(int(dest[0]), int(dest[1]), 0, 0)   # 空纹理直接跳过，别让 SDL 报错
        rect = pygame.Rect(int(dest[0]), int(dest[1]), w, h)
        gpu_renderer.blit(self.tex(src), rect, area)
        return rect
    def rect(self, color, r, width=0):
        r = pygame.Rect(r)
        gpu_renderer.draw_color = _c4(color)
        if width and width > 0:
            w = max(1, int(width))
            gpu_renderer.fill_rect(pygame.Rect(r.x, r.y, r.w, w))
            gpu_renderer.fill_rect(pygame.Rect(r.x, r.bottom - w, r.w, w))
            gpu_renderer.fill_rect(pygame.Rect(r.x, r.y, w, r.h))
            gpu_renderer.fill_rect(pygame.Rect(r.right - w, r.y, w, r.h))
        else:
            gpu_renderer.fill_rect(r)
        return r
    def line(self, color, a, b, width=1):
        gpu_renderer.draw_color = _c4(color)
        w = max(1, int(width))
        if abs(a[1] - b[1]) <= w:
            gpu_renderer.fill_rect(pygame.Rect(min(a[0], b[0]), int(a[1]) - w // 2, abs(b[0] - a[0]) + 1, w))
        else:
            gpu_renderer.fill_rect(pygame.Rect(int(a[0]) - w // 2, min(a[1], b[1]), w, abs(b[1] - a[1]) + 1))
    def clear_tex(self):
        self._tex.clear(); self._order.clear()

def ui_rect(target, color, r, width=0):
    """UI 矩形：GPU 模式走纹理填充，软件模式走原 pygame.draw"""
    if isinstance(target, GpuTarget): return target.rect(color, r, width)
    if len(color) > 3 and color[3] < 255:
        # 软件模式普通 Surface 上的 draw.rect 会忽略 alpha，细边框会变成死白；
        # 先画到 SRCALPHA 临时面再贴，透明才真的按 alpha 混
        tmp = pygame.Surface((max(1, r.width), max(1, r.height)), pygame.SRCALPHA)
        pygame.draw.rect(tmp, color, pygame.Rect(0, 0, r.width, r.height), width)
        target.blit(tmp, r.topleft)
        return
    return pygame.draw.rect(target, color, r, width)

def ui_line(target, color, a, b, width=1):
    if isinstance(target, GpuTarget): return target.line(color, a, b, width)
    if len(color) > 3 and color[3] < 255:
        x0, y0 = min(a[0], b[0]), min(a[1], b[1])
        w_, h_ = abs(b[0] - a[0]) + width, abs(b[1] - a[1]) + width
        tmp = pygame.Surface((max(1, w_), max(1, h_)), pygame.SRCALPHA)
        pygame.draw.line(tmp, color, (a[0] - x0, a[1] - y0), (b[0] - x0, b[1] - y0), width)
        target.blit(tmp, (x0, y0))
        return
    return pygame.draw.line(target, color, a, b, width)

_alpha_cache = {}

def ui_fill_alpha(target, r, color):
    """半透明实心矩形（压暗层 / 分组卡片）。软件模式按尺寸+颜色复用面，不每帧重建"""
    if isinstance(target, GpuTarget):
        prev = gpu_renderer.draw_blend_mode
        gpu_renderer.draw_blend_mode = pygame.BLENDMODE_BLEND
        gpu_renderer.draw_color = color
        gpu_renderer.fill_rect(srect(r))
        gpu_renderer.draw_blend_mode = prev
        return
    sr = srect(r)
    key = (sr.width, sr.height, color)
    s = _alpha_cache.get(key)
    if s is None:
        s = pygame.Surface((max(1, sr.width), max(1, sr.height)), pygame.SRCALPHA)
        s.fill(color)
        if len(_alpha_cache) > 40: _alpha_cache.clear()
        _alpha_cache[key] = s
    target.blit(s, sr.topleft)

_bg_gpu = {"tex": None, "dx": None, "oy": None}
_gpu_blur_cache = {}

# 场景纹理：游戏世界先画进这里，再整块贴到屏幕；按钮磨砂从"已经画完的画面"上取样，
# 所以人物、障碍物、金币都会被糊进去，而不是只有背景
_scene = {"tex": None, "size": None}

def scene_begin():
    if _scene["tex"] is None or _scene["size"] != WIN_SIZE():
        _scene["tex"] = new_tex(WIN_SIZE(),
                                                   target=True, scale_quality=1)
        _scene["size"] = WIN_SIZE()
    gpu_renderer.target = _scene["tex"]
    gpu_renderer.draw_color = (0, 0, 0, 255)
    gpu_renderer.clear()

def scene_end():
    gpu_renderer.target = None
    gpu_renderer.blit(_scene["tex"], pygame.Rect(0, 0, WIN_SIZE()[0], WIN_SIZE()[1]))

def scene_flush():
    _scene["tex"], _scene["size"] = None, None
    _frost_gen[0] = 0

_frost_gen = [0]
BLUR_PAGE_PX = 7                  # 页面/背景：1.5x -> 约 10px 半径，淡淡一层
BLUR_CTRL_PX = 26                 # 游戏内控件（跳/蹲/左右）：大模糊
BLUR_PAUSE_PX = 40                # 暂停按钮：面积小，直接糊成一片
# 模糊档位（只在设置页切换）：轻 / 中 / 重，乘到上面三个基准半径上
BLUR_LEVELS = [("弱", 0.45), ("中", 1.0), ("强", 1.65)]
blur_level = 1
def blur_mul():
    return BLUR_LEVELS[max(0, min(len(BLUR_LEVELS) - 1, blur_level))][1]
def page_blur_px():
    # 所有页面底图（设置/暂停/死亡/数据）共用这个半径，别再各写各的：
    # 高斯核只有 31 个抽样，半径一大抽样间隔就被拉开，画面会出现网格
    return max(4, int(round(BLUR_PAGE_PX * 1.5 * blur_mul())))

_frost_chain_cache = {}
def _frost_chain(w, h, radius):
    # 逐级 1/2 降采样金字塔：每级都是硬件 2x2 盒式平均（多次盒式平均 = 高斯）
    key = (w, h, radius, _frost_gen[0])
    ent = _frost_chain_cache.get(key)
    if ent is not None: return ent
    stop = max(2, int(round(min(w, h) / max(1.0, float(radius)))))
    levels = max(1, int(round(math.log(min(w, h) / stop, 2))) + 1) if stop < min(w, h) else 1
    sizes, cw, ch = [], w, h
    for _ in range(min(levels, 9)):
        cw, ch = max(2, cw // 2), max(2, ch // 2)
        sizes.append((cw, ch))
    if len(_frost_chain_cache) > 40: _frost_chain_cache.clear()
    tex = [new_tex(s, target=True) for s in sizes]
    _frost_chain_cache[key] = tex
    return tex

def blur_region(src_tex, src_rect, dst_w, dst_h, radius=None, exact=True, dst_tex=None, up_chain=True):
    """模糊入口。GL 后端 = 显卡上的两遍可分离高斯卷积（1:1 采样，零缩放）；
    SDL 后端 = 1/2 金字塔降采样（多次盒式平均≈高斯）→ 2 倍逐级升采样。"""
    if radius is None: radius = 10
    w, h = int(dst_w), int(dst_h)
    if glsl_active:
        return gpu_renderer.gauss_blur(src_tex, src_rect, w, h, float(radius), dst_tex), w, h
    chain = _frost_chain(w, h, float(radius))
    prev = gpu_renderer.target
    gpu_renderer.target = chain[0]
    gpu_renderer.draw_blend_mode = pygame.BLENDMODE_BLEND
    gpu_renderer.draw_color = (255, 255, 255, 255)
    gpu_renderer.blit(src_tex, pygame.Rect(0, 0, chain[0].width, chain[0].height), src_rect)
    for i in range(1, len(chain)):
        gpu_renderer.target = chain[i]
        gpu_renderer.blit(chain[i - 1], pygame.Rect(0, 0, chain[i].width, chain[i].height))
    last = chain[-1]
    if up_chain:
        for i in range(len(chain) - 1, 0, -1):
            t = chain[i - 1]
            gpu_renderer.target = t
            gpu_renderer.blit(last, pygame.Rect(0, 0, t.width, t.height))
            last = t
    gpu_renderer.target = prev
    if dst_tex is not None:
        # 结果要长期持有（页面底图），整块 1:1 拷进调用方自己的纹理
        gpu_renderer.target = dst_tex
        gpu_renderer.draw_color = (255, 255, 255, 255)
        gpu_renderer.blit(last, pygame.Rect(0, 0, w, h))
        gpu_renderer.target = prev
        return dst_tex, w, h
    return last, w, h


def gpu_frost(surface, r, blur_px_value=None):
    """游戏内按钮的实时磨砂：从已经画完的场景纹理取这一块，每帧一次"""
    src_tex = _scene["tex"]
    if src_tex is None: return False
    src = pygame.Rect(int(r.x), int(r.y), int(r.width), int(r.height))
    src = src.clip(pygame.Rect(0, 0, src_tex.width, src_tex.height))
    if src.width <= 0 or src.height <= 0: return False
    if blur_px_value is None: blur_px_value = int(round(BLUR_CTRL_PX * blur_mul()))
    tex, w, h = blur_region(src_tex, src, r.width, r.height, radius=int(blur_px_value))
    surface.blit(GpuFrost(tex, w, h), r.topleft)
    return True

def build_gpu_blur(tint_key, tint_color):
    # 页面底图：GPU 上对场景纹理做金字塔模糊 + 加色叠色，不回读屏幕、不用软件缩放。
    # 黑边只是填充区：只模糊/只叠色画布那一段，四周保持纯黑，也不让模糊把边上的黑渗进画面
    w, h = WIN_SIZE()
    cw, ch = max(1, draw_w), max(1, draw_h)
    cx, cy = int(off_x), int(off_y)
    src_tex = _scene["tex"]
    if src_tex is None: return None
    r = page_blur_px()
    # 源矩形往画布里缩 r 像素再模糊（模糊本身自带缩放，输出仍是整块画布）：
    # 贴着画布边取样会把边外的纯黑吃进来，画面四周发暗
    inner = pygame.Rect(cx + r, cy + r, max(1, cw - 2 * r), max(1, ch - 2 * r))
    canvas_tex = new_tex((cw, ch), target=True)
    blur_region(src_tex, inner, cw, ch, radius=r, dst_tex=canvas_tex)
    tex = new_tex((w, h), target=True)
    prev = gpu_renderer.target
    mode = gpu_renderer.draw_blend_mode
    gpu_renderer.target = tex
    gpu_renderer.draw_blend_mode = pygame.BLENDMODE_NONE
    gpu_renderer.draw_color = (0, 0, 0, 255)
    gpu_renderer.clear()                      # 边条铺黑
    gpu_renderer.blit(canvas_tex, pygame.Rect(cx, cy, cw, ch))
    gpu_renderer.draw_blend_mode = pygame.BLENDMODE_ADD
    gpu_renderer.draw_color = (tint_color[0], tint_color[1], tint_color[2], 255)
    gpu_renderer.fill_rect(pygame.Rect(cx, cy, cw, ch))
    gpu_renderer.draw_blend_mode = mode
    gpu_renderer.target = prev
    return GpuBlur(tex, w, h)

# ---------- 后端初始化：GLSL 直连 → SDL 纹理 → 软件渲染，逐级回退 ----------
glsl_active = False
GPU_INIT_ERR = ""       # 打包后掉回软件渲染时，靠这条记录排查（写进 WUGUO_LOG）
if RENDER_BACKEND != "soft":
    try:
        if RENDER_BACKEND == "gl" and GL_AVAILABLE:
            gpu_win = pygame.Window("超级吴国鸡跑酷", size=(1280, 678), resizable=True, opengl=True)
            gpu_renderer = GLRenderer(gpu_win)
        else:
            gpu_win = pygame.Window("超级吴国鸡跑酷", size=(1280, 678), resizable=True)
            gpu_renderer = pygame._sdl2.video.Renderer(gpu_win, accelerated=-1)
        gpu_renderer.draw_blend_mode = pygame.BLENDMODE_BLEND
        GPU_OK = True
    except Exception as _e:
        GPU_INIT_ERR = repr(_e)
        print("[GPU] 初始化失败，回退软件渲染: %r" % (_e,))
        try: gpu_win.destroy()
        except Exception: pass
        gpu_win = gpu_renderer = None
glsl_active = GPU_OK and isinstance(gpu_renderer, GLRenderer)
_win_px = tuple(gpu_win.size) if GPU_OK else (0, 0)

def current_backend_key():
    """实际在跑的后端：gl（GLSL 直连）/ gpu（SDL 纹理降级）/ soft（软件）"""
    if not GPU_OK: return "soft"
    return "gl" if glsl_active else "gpu"

def current_backend_name():
    return "OpenGL" if GPU_OK else "软件加速"

def relaunch_game():
    # 换渲染后端要重建窗口和 GL 上下文，重启本进程最干净
    try:
        if getattr(sys, "frozen", False):
            # 单文件 exe 不能用 execv：新进程会继承 _MEIPASS2 / _PYI_* 这些变量，
            # 于是复用父进程的 _MEI 解包目录；父进程一退就把那个目录删了，
            # 新进程就读不到 assets（字体、背景全丢）。改成干净启动一个新进程。
            # 同时剥掉 WUGUO_RENDER：不然子进程会沿用本次启动时的后端，界面上的改动不生效。
            env = {k: v for k, v in os.environ.items()
                   if not (k.startswith("_PYI") or k in ("_MEIPASS2", "WUGUO_RENDER"))}
            subprocess.Popen([sys.executable] + sys.argv[1:], env=env, close_fds=True)
            os._exit(0)
        os.execv(sys.executable, [sys.executable, os.path.abspath(__file__)] + sys.argv[1:])
    except Exception as _e:
        print("[RENDER] 自动重启失败: %r" % (_e,))

# 排查用：WUGUO_SELFTEST=relaunch 时启动即走一次上面的自动重启，验证 exe 重启后资源不丢
if os.environ.get("WUGUO_SELFTEST") == "relaunch":
    os.environ["WUGUO_SELFTEST"] = "relaunched"
    relaunch_game()

GL_PROBE_VERSION = ""
GL_PROBE_ERR = ""
def probe_opengl():
    """起一个 64×64 隐藏 GL 窗口，确认真能拿到 3.3+ 上下文（着色器写的是 #version 330）"""
    global GL_PROBE_VERSION, GL_PROBE_ERR
    if not GL_AVAILABLE:
        return False
    prob = None
    try:
        prob = pygame.Window("gl-probe", size=(64, 64), opengl=True, hidden=True)
        ver = (gl.glGetString(gl.GL_VERSION) or b"").decode(errors="ignore")
        major, minor = [int(x) for x in ver.split(".")[:2]]
        ok = major >= 4 or (major == 3 and minor >= 3)
        GL_PROBE_VERSION = ver
        print("[GL] 探测: OpenGL %s -> %s" % (ver.split(" ")[0], "可用" if ok else "版本过低"))
        return ok
    except Exception as _e:
        GL_PROBE_ERR = repr(_e)
        print("[GL] 探测失败，判定不支持 OpenGL: %r" % (_e,))
        return False
    finally:
        if prob is not None:
            try: prob.destroy()
            except Exception: pass

# 软件模式下现场探测一次：驱动给不出 3.3+ 上下文就把 OpenGL 这一项灰掉
OPENGL_OK = True if GPU_OK else probe_opengl()

def write_backend_log():
    """WUGUO_LOG=<路径> 时把启动时的后端信息写一份，排查打包/驱动问题用"""
    path = os.environ.get("WUGUO_LOG")
    if not path: return
    try:
        ver = ""
        if GPU_OK and GL_AVAILABLE:
            try: ver = (gl.glGetString(gl.GL_VERSION) or b"").decode(errors="ignore")
            except Exception: pass
        if not ver: ver = GL_PROBE_VERSION
        with io.open(path, "w", encoding="utf-8") as fh:
            fh.write("backend=%s\nGPU_OK=%s\nglsl_active=%s\nOPENGL_OK=%s\nGL_VERSION=%s\nGPU_INIT_ERR=%s\nGL_PROBE_ERR=%s\n"
                     % (current_backend_key(), GPU_OK, glsl_active, OPENGL_OK, ver, GPU_INIT_ERR, GL_PROBE_ERR))
    except Exception:
        pass
write_backend_log()

def new_tex(size, target=False, streaming=False, scale_quality=1):
    if glsl_active:
        return GLTexture(gpu_renderer, size, target=target, streaming=streaming)
    return pygame._sdl2.video.Texture(gpu_renderer, size, target=target,
                                      streaming=streaming, scale_quality=scale_quality)

def tex_from_surface(surf):
    if glsl_active:
        return GLTexture.from_surface(gpu_renderer, surf)
    return pygame._sdl2.video.Texture.from_surface(gpu_renderer, surf)

gpu_target = GpuTarget() if GPU_OK else None

def WIN_SIZE():
    return tuple(gpu_win.size) if GPU_OK else win.get_size()

if GPU_OK:
    win = gpu_win
    screen = gpu_target
else:
    win = pygame.display.set_mode((1280, 678), pygame.RESIZABLE)
    screen = win
    pygame.display.set_caption("超级吴国鸡跑酷")
clock = pygame.time.Clock()
# 初始化坐标映射缓存
update_mapping(*WIN_SIZE())

# ---------- 相对坐标渲染层：设计坐标(1920x1017) -> 任意窗口 ----------
def sx(v): return int(off_x + v * scale)
def sy(v): return int(off_y + v * scale)
def px(v): return int(round(v * scale))   # 只算像素、不带偏移，供统一世界偏移使用
def world_x(x):
    # 世界坐标→窗口x：三模式减镜头；零/一模式没有镜头，直接用世界坐标（背景相位与它无关）
    # 旧写法把 px(bg_x) 与 round(x-bg_x) 两个独立取整相加，会产生 0/-1/+1 三帧锯齿抖动，已去掉
    if game_state["game_mode"] == 2:
        return off_x + int(round((x - game_state["cam_x"]) * scale))
    return off_x + int(round(x * scale))
def srect(r):
    return pygame.Rect(sx(r[0]), sy(r[1]), max(1, int(r[2] * scale)), max(1, int(r[3] * scale)))
_scaled_cache = {}
def ds(surf):
    # 图片按当前缩放比例缓存一份缩放版，blit 时不再整屏缩放
    if surf is None: return None
    if abs(scale - 1.0) < 1e-6: return surf
    key = (id(surf), round(scale, 4))
    ent = _scaled_cache.get(key)
    if ent is None or ent[0] is not surf:
        if len(_scaled_cache) > 600: _scaled_cache.clear()
        s = pygame.transform.smoothscale(surf, (max(1, int(surf.get_width() * scale)),
                                                max(1, int(surf.get_height() * scale))))
        ent = (surf, s)
        _scaled_cache[key] = ent
    return ent[1]

try:
    icon_img = pygame.image.load(get_asset_path("icon32.ico"))
    if GPU_OK: gpu_win.set_icon(icon_img)
    else: pygame.display.set_icon(icon_img)
except Exception: pass
_bg_scaled = {"key": None, "surf": None}
def bg_scaled(bg, w, h):
    """背景图必须正好是 px(DESIGN_W) × px(DESIGN_H)。
    ds() 是按 scale 乘出来再取整的，缩放比来自高度时宽度会少 1 像素，
    两块拼在一起就出现一条竖缝——高分辨率/超宽窗口下最明显。"""
    if bg is None: return None
    key = (id(bg), w, h)
    ent = _bg_scaled
    if ent["key"] != key or ent["surf"] is None or ent["surf"].get_width() != w or ent["surf"].get_height() != h:
        ent["surf"] = bg if bg.get_size() == (w, h) else pygame.transform.smoothscale(bg, (w, h))
        ent["key"] = key
    return ent["surf"]

_font_cache = {}
def font_pt(pt):
    # 字号也按相对比例重建，避免小窗口下文字撑破控件
    key = (pt, round(scale, 3))
    f = _font_cache.get(key)
    if f is None:
        if len(_font_cache) > 40: _font_cache.clear()
        f = get_chinese_font(max(9, int(round(pt * scale))))
        _font_cache[key] = f
    return f
font = font_pt(32)
font_big = font_pt(48)
font_small = font_pt(24)
NOMINAL = {id(font): (font, 32), id(font_big): (font_big, 48), id(font_small): (font_small, 24)}
def nominal_of(f):
    # 值里抓住字体本体，避免字体被回收、id 被复用后拿到别人的设计字号
    ent = NOMINAL.get(id(f))
    return ent[1] if ent is not None and ent[0] is f else 32
_text_cache = {}
def text_surf(fnt, text, color):
    # 文字缓存：同样字符串不再重复 render（HUD 分数/设置页标签每帧都在重绘）
    # 值里连字体一起存，防止字体对象回收后 id 被复用导致串字
    key = (id(fnt), text, color)
    ent = _text_cache.get(key)
    if ent is None or ent[0] is not fnt:
        if len(_text_cache) > 400: _text_cache.clear()
        if text == "":
            # 空串会渲染出 0 宽的面，贴到纹理上会炸（纹理尺寸不能为 0）
            surf = pygame.Surface((1, max(1, fnt.get_height())), pygame.SRCALPHA)
        else:
            surf = fnt.render(text, True, color)
        ent = (fnt, surf)
        _text_cache[key] = ent
    return ent[1]
_blur_cache = {"surf": None}
ui_dirty = True
font_reset = font_pt(28)
font_input = font_pt(26)
font_lock = font_pt(96)
def get_symbol_font(size):
    p = get_asset_path("NotoSansSC.ttf")
    if os.path.exists(p):
        try: return pygame.font.Font(p, size)
        except Exception: pass
    windir = os.environ.get("WINDIR", r"C:\Windows")
    for name in ("seguisym.ttf", "segoeuisl.ttf", "arial.ttf"):
        q = os.path.join(windir, "Fonts", name)
        if os.path.exists(q):
            try: return pygame.font.Font(q, size)
            except Exception: continue
    return get_chinese_font(size)
font_del = get_symbol_font(max(9, int(round(62 * scale))))
NOMINAL.update({id(font_reset): (font_reset, 28), id(font_input): (font_input, 26), id(font_lock): (font_lock, 96)})
bg_img = load_img("bg.jpg", DESIGN_W, DESIGN_H)
if bg_img and pygame.display.get_surface() is not None: bg_img = bg_img.convert()
TARGET_HEIGHT = 425
GROUND_Y = DESIGN_H - TARGET_HEIGHT
# 玩家资源预加载
player_frames_right = [load_img_scale_height(f"run{i}.png", TARGET_HEIGHT) for i in range(3)]
player_frames_right = [f for f in player_frames_right if f]
player_frames_left = [pygame.transform.flip(f, True, False) for f in player_frames_right]
player_masks_right = [pygame.mask.from_surface(f) for f in player_frames_right]
player_masks_left = [pygame.mask.from_surface(f) for f in player_frames_left]
# 红温资源预生成
hurt_frames_right, hurt_masks_right = [], []
hurt_frames_left, hurt_masks_left = [], []
for i, f in enumerate(player_frames_right):
    mask = player_masks_right[i]
    red_overlay = pygame.Surface(f.get_size(), pygame.SRCALPHA)
    red_overlay.fill((255, 0, 0, 90))
    red_img = f.copy()
    red_img.blit(mask.to_surface(surface=red_overlay, setcolor=(255, 0, 0, 90), unsetcolor=(0, 0, 0, 0)), (0, 0))
    hurt_frames_right.append(red_img)
    hurt_masks_right.append(pygame.mask.from_surface(red_img))
    left_img = pygame.transform.flip(red_img, True, False)
    hurt_frames_left.append(left_img)
    hurt_masks_left.append(pygame.mask.from_surface(left_img))
coin_img = load_img("coin.png", 150, 150) or pygame.Surface((150, 150), pygame.SRCALPHA)
if not os.path.exists(get_asset_path("coin.png")): pygame.draw.circle(coin_img, (255, 215, 0), (24, 24), 20)
coin_mask = pygame.mask.from_surface(coin_img)
# 蹲下资源（sit.png 锁定姿态，含左右与红温版本）
sit_img_right = load_img_scale_height("sit.png", TARGET_HEIGHT)
if sit_img_right is None: sit_img_right = player_frames_right[0].copy()
sit_img_left = pygame.transform.flip(sit_img_right, True, False)
sit_mask_right = pygame.mask.from_surface(sit_img_right)
sit_mask_left = pygame.mask.from_surface(sit_img_left)
hurt_sit_right = sit_img_right.copy()
red_overlay_sit = pygame.Surface(sit_img_right.get_size(), pygame.SRCALPHA)
red_overlay_sit.fill((255, 0, 0, 90))
hurt_sit_right.blit(sit_mask_right.to_surface(surface=red_overlay_sit, setcolor=(255, 0, 0, 90), unsetcolor=(0, 0, 0, 0)), (0, 0))
hurt_sit_mask_right = pygame.mask.from_surface(hurt_sit_right)
hurt_sit_left = pygame.transform.flip(hurt_sit_right, True, False)
hurt_sit_mask_left = pygame.mask.from_surface(hurt_sit_left)
obs_pool = [img for i in range(22) if (img := load_img_scale_height(f"ob{i}.png", TARGET_HEIGHT))]
hit_sounds = [s for s in [load_snd(f"hit{i}.ogg") for i in range(1, 11)] if s]
death_sounds = [s for s in [load_snd(f"death{i}.ogg") for i in range(1, 6)] if s]
# ---------- 2. 渲染层：坐标换算 / 玻璃材质 / 模糊 ----------
class BlurSurface:
    def __init__(self, width, height, blur_strength=8):
        self.width = width
        self.height = height
        self.blur_strength = blur_strength

    def get_region(self, source_surface, rect, strength=None):
        # 只对目标矩形区域做模糊：不整屏缩小/放大，省掉两次全画布 smoothscale
        clip = rect.clip(source_surface.get_rect())
        if clip.width <= 0 or clip.height <= 0: return pygame.Surface((1, 1), pygame.SRCALPHA)
        st = strength or self.blur_strength
        small = pygame.transform.smoothscale(source_surface.subsurface(clip),
                                             (max(1, int(clip.width / st)),
                                              max(1, int(clip.height / st))))
        return pygame.transform.smoothscale(small, (clip.width, clip.height))
_glass_cache = {}
_glass_overlay_cache = {}
_btn_glass_cache = {}      # 游戏内按钮的模糊底：隔帧复用，省一半 smoothscale
_frame_no = [0]
_bg_tile = {"surf": None, "key": None}   # 2 倍宽背景横条，省掉每帧第二次补条 blit
_blur_gen = [0]
_gpu_glass_cache = {}
def _rim_width(is_dark):
    # 暗玻璃只留极细一圈；亮玻璃给一圈明显的高光边，贴着背景也分得开
    return max(1, int(round(scale)))
def _rim_color(is_dark, a=132):
    if is_dark: return (255, 255, 255, 72) if a != 132 else (255, 255, 255, 30)
    return (255, 255, 255, 58)
def _glass_fill_color(is_pressed, is_dark, a):
    """玻璃叠色：亮材质偏白、暗材质偏蓝灰，按下时更实一点"""
    if is_dark:
        if a == 132:
            return (20, 25, 35, 226) if is_pressed else (30, 35, 45, 196)
        return (88, 96, 110, min(255, a + 25)) if is_pressed else (72, 80, 94, a)
    return (252, 252, 254, min(255, a + 33)) if is_pressed else (252, 252, 254, a)

def _overlay_img(w, h, is_pressed, is_dark, a):
    """叠色层：只跟尺寸/状态有关，缓存后直接用"""
    key = (w, h, is_pressed, is_dark, a)
    img = _glass_overlay_cache.get(key)
    if img is None:
        img = pygame.Surface((max(1, w), max(1, h)), pygame.SRCALPHA)
        img.fill(_glass_fill_color(is_pressed, is_dark, a))
        if len(_glass_overlay_cache) > 60: _glass_overlay_cache.clear()
        _glass_overlay_cache[key] = img
    return img

_rim_cache = {}

def _rim_img(w, h, is_dark, a):
    """直角细描边，按尺寸缓存，避免每帧新建临时面"""
    key = (w, h, is_dark, a)
    img = _rim_cache.get(key)
    if img is None:
        img = pygame.Surface((max(1, w), max(1, h)), pygame.SRCALPHA)
        pygame.draw.rect(img, _rim_color(is_dark, a), img.get_rect(), _rim_width(is_dark))
        if len(_rim_cache) > 80: _rim_cache.clear()
        _rim_cache[key] = img
    return img

def _gpu_glass_img(r, is_pressed, is_dark, a=132):
    # GPU 页面：底图已经在显存里，这里只需要一张烘好的叠色+描边（整块一次贴完）
    key = (r.width, r.height, is_pressed, is_dark, a)
    img = _gpu_glass_cache.get(key)
    if img is None:
        img = pygame.Surface((r.width, r.height), pygame.SRCALPHA)
        img.fill(_glass_fill_color(is_pressed, is_dark, a))
        pygame.draw.rect(img, _rim_color(is_dark, a), img.get_rect(), _rim_width(is_dark))
        if len(_gpu_glass_cache) > 40: _gpu_glass_cache.clear()
        _gpu_glass_cache[key] = img
    return img
def draw_ios_glass(surface, rect, blur_surf, is_pressed=False, is_dark=False,
                   strength=None, blur_px_value=None, light_alpha=None):
    """玻璃材质块：底 = 模糊层切片，面 = 叠色，边 = 一圈高光。rect 用设计坐标"""
    a = 132 if light_alpha is None else light_alpha
    r = srect(rect)
    if r.width <= 0 or r.height <= 0:
        return
    if isinstance(surface, GpuTarget):
        # GPU 路径：底图从模糊层切片（页面），或每帧做一次磨砂（游戏内）
        if isinstance(blur_surf, GpuBlur):
            surface.blit(blur_surf, r.topleft, pygame.Rect(r.x, r.y, r.width, r.height))
        else:
            gpu_frost(surface, r, blur_px_value)
        surface.blit(_gpu_glass_img(r, is_pressed, is_dark, a), r.topleft)
        return
    clip_rect = r.clip(surface.get_rect())
    if clip_rect.width <= 0 or clip_rect.height <= 0:
        return
    if isinstance(blur_surf, BlurSurface):
        # 游戏内：背景每帧只滚 20px，模糊底隔帧复用完全看不出差别，省一半区域缩放
        bkey = (clip_rect.x, clip_rect.y, clip_rect.width, clip_rect.height, strength)
        ent = _btn_glass_cache.get(bkey)
        if ent is None or _frame_no[0] - ent[0] >= 2:
            ent = (_frame_no[0], blur_surf.get_region(surface, clip_rect, strength))
            if len(_btn_glass_cache) > 40: _btn_glass_cache.clear()
            _btn_glass_cache[bkey] = ent
        surface.blit(ent[1], r.topleft)
    else:
        # 静止界面：底图不刷新，玻璃块连叠色带描边一起烘进缓存，每帧只剩一次贴图
        key = (_blur_gen[0], clip_rect.x, clip_rect.y, clip_rect.width, clip_rect.height,
               is_pressed, is_dark, a)
        glass_img = _glass_cache.get(key)
        if glass_img is None:
            glass_img = blur_surf.subsurface(clip_rect).copy()
            glass_img.blit(_overlay_img(clip_rect.width, clip_rect.height, is_pressed, is_dark, a), (0, 0))
            glass_img.blit(_rim_img(clip_rect.width, clip_rect.height, is_dark, a), (0, 0))
            if len(_glass_cache) > 300: _glass_cache.clear()
            _glass_cache[key] = glass_img
        surface.blit(glass_img, r.topleft)
        return
    surface.blit(_overlay_img(r.width, r.height, is_pressed, is_dark, a), r.topleft)
    surface.blit(_rim_img(r.width, r.height, is_dark, a), r.topleft)

class GlassButton:
    def __init__(self, rect, text, font_size=font, is_dark=False):
        self.rect = pygame.Rect(rect)
        self.text = text
        self.pt = nominal_of(font_size)   # 设计字号，缩放变化时按它重建字体
        self.font = font_size
        self.is_dark = is_dark
        self.pressed = False
        self.blur_strength = None      # 游戏内控件设得更小 = 模糊更重，文字更立得住
        self.blur_px_value = None      # GPU 路径的模糊半径；None = 游戏内控件默认大模糊
        self.glass_alpha = None        # 亮玻璃不透明度：弹层里调高，所有按钮颜色才一致
        self.text_color = (255, 255, 255)
        self.selected = False          # 选项按钮的选中态：只加白框，不改字色
        self._style_key = None
        self.font_surf = self.font.render(self.text, True, self.text_color)
    def refresh(self):
        self.font = font_pt(self.pt)
        self.font_surf = self.font.render(self.text, True, self.text_color)
        self._style_key = None
    def handle_event(self, event):
        if event.type == pygame.MOUSEBUTTONDOWN and event.button == 1:
            # 鼠标坐标已经提前转换为虚拟画布坐标
            if self.rect.collidepoint(event.pos): self.pressed = True
        elif event.type == pygame.FINGERDOWN:
            tx, ty = event.x * DESIGN_W, event.y * DESIGN_H
            if self.rect.collidepoint((tx, ty)): self.pressed = True
        elif event.type in (pygame.MOUSEBUTTONUP, pygame.FINGERUP):
            if self.pressed:
                self.pressed = False
                pos = event.pos if event.type == pygame.MOUSEBUTTONUP else (event.x * DESIGN_W, event.y * DESIGN_H)
                if self.rect.collidepoint(pos): return True
        elif event.type == pygame.MOUSEMOTION and self.pressed:
            if not self.rect.collidepoint(event.pos): self.pressed = False
        return False
    def draw(self, screen, blur_surf):
        draw_ios_glass(screen, self.rect, blur_surf, is_pressed=self.pressed, is_dark=self.is_dark,
                       strength=self.blur_strength, blur_px_value=self.blur_px_value, light_alpha=self.glass_alpha)
        r = srect(self.rect)
        if self.selected:
            # 选中态：先铺一层很淡的白，再套 2px 细框；玻璃和文字都不动，点选时字不会变
            ui_fill_alpha(screen, self.rect, (255, 255, 255, 34))
            ui_rect(screen, (255, 255, 255, 178), r, max(2, int(round(2 * scale))))
        if not self.is_dark:
            # 亮玻璃配白字会糊，直接换深色字（状态类按钮走 is_dark=True，不受影响）
            t = text_surf(self.font, self.text, (30, 34, 40))
        else:
            t = self.font_surf
        screen.blit(t, t.get_rect(center=(r.centerx, r.centery + 1)))

def style_option(btn, selected):
    """选中=暗玻璃+白框，未选=暗玻璃。文字颜色恒定，点选时字不会变"""
    key = (selected, btn.text)
    if btn._style_key == key and btn.is_dark: return
    btn.selected = selected
    btn.is_dark = True                 # 选项按钮一律暗玻璃，白字才看得清
    btn.text_color = (255, 255, 255)
    btn.font_surf = btn.font.render(btn.text, True, btn.text_color)
    btn._style_key = key
class InputBox:
    def __init__(self, x, y, w, h, default_text="", is_int=False):
        self.rect = pygame.Rect(x, y, w, h)
        self.text = default_text
        self.is_int = is_int
        self.active = False
        self.sel_all = False        # 点进来先整段选中：直接敲数字就等于「删掉重输」
        self.font_surf = None
        self.last_text = ""

        btn_size = 44
        self.btn_minus = GlassButton((x - btn_size - 10, y, btn_size, h), "-", font_size=font, is_dark=True)
        self.btn_plus = GlassButton((x + w + 10, y, btn_size, h), "+", font_size=font, is_dark=True)

    def _commit(self, value_text):
        """把当前文本落地成数字，顺手校验范围，返回是否合法"""
        try:
            v = float(value_text)
        except (TypeError, ValueError):
            return False
        v = max(-9999.5, min(9999.5, v))
        self.text = str(int(v)) if self.is_int else str(round(v, 2))
        return True

    def handle_event(self, event):
        #如果是整数参数，按 1 步进；如果是小数，按 0.5 步进
        step = 1 if self.is_int else 0.5

        if self.btn_minus.handle_event(event):
            try:
                val = max(-9999.5, float(self.text) - step)
                self.text = str(int(val)) if self.is_int else str(round(val, 2))
                return True
            except ValueError: pass
        if self.btn_plus.handle_event(event):
            try:
                val = min(9999.5, float(self.text) + step)
                self.text = str(int(val)) if self.is_int else str(round(val, 2))
                return True
            except ValueError: pass
        if event.type == pygame.MOUSEBUTTONDOWN:
            hit = self.rect.collidepoint(event.pos)
            if self.active and not hit:
                self._commit(self.text)      # 点到别处 = 失焦，先把值落地
            self.active = hit
            self.sel_all = hit            # 点一下就整段选中，敲的新值直接顶替旧值
        if event.type == pygame.FINGERDOWN:
            tx, ty = event.x * DESIGN_W, event.y * DESIGN_H
            hit = self.rect.collidepoint((tx, ty))
            if self.active and not hit:
                self._commit(self.text)
            self.active = hit
            self.sel_all = hit

        if event.type == pygame.KEYDOWN and self.active:
            mods = pygame.key.get_mods()
            changed = False
            if event.key == pygame.K_BACKSPACE:
                self.text = "" if self.sel_all else self.text[:-1]
                self.sel_all = False
                changed = True
            elif event.key == pygame.K_DELETE:
                self.text = self.text[:-1] if not self.sel_all else ""
                self.sel_all = False
                changed = True
            elif event.key == pygame.K_a and (mods & pygame.KMOD_CTRL):
                self.sel_all = True          # Ctrl+A 全选
            elif event.key in (pygame.K_RETURN, pygame.K_KP_ENTER, pygame.K_ESCAPE):
                self.active = False          # 回车/ESC 收工，提交给 cfg
                self.sel_all = False
                self._commit(self.text)
                return True
            elif event.unicode in "0123456789.-":
                if self.sel_all:
                    self.text = event.unicode
                    self.sel_all = False
                else:
                    self.text += event.unicode
                changed = True
            if changed:
                if len(self.text) > 12:
                    self.text = self.text[:12]
                return True
        return False
    def update(self):
        if self.text != self.last_text:
            self.last_text = self.text
            self.font_surf = text_surf(font_input, self.text, (255, 255, 255))
    def refresh(self):
        self.font_surf = None
        self.last_text = ""
        self.btn_minus.refresh()
        self.btn_plus.refresh()
    def draw(self, screen, blur_surf):
        self.btn_minus.draw(screen, blur_surf)
        self.btn_plus.draw(screen, blur_surf)
        draw_ios_glass(screen, self.rect, blur_surf, is_pressed=False, is_dark=True)
        r = srect(self.rect)
        if self.active: ui_rect(screen, (0, 122, 255), r, max(1, int(round(2 * scale))))
        if not self.font_surf:
            # 空内容也要画光标，否则点进去看不出已经可以输入
            if self.active and int(time.time() * 2) % 2 == 0:
                ui_rect(screen, (255, 255, 255), pygame.Rect(r.x + int(15 * scale), r.centery - int(10 * scale),
                                                             max(2, int(2 * scale)), int(20 * scale)))
            return
        tw = self.font_surf.get_width()
        if self.sel_all and self.text:
            # 整段选中：给一层高亮，告诉用户「再敲一位就把旧的顶掉」
            hl = pygame.Rect(r.x + int(13 * scale), r.y + int(6 * scale),
                             min(tw + int(4 * scale), r.width - int(26 * scale)), r.height - int(12 * scale))
            hl_surf = pygame.Surface(hl.size, pygame.SRCALPHA)
            hl_surf.fill((0, 122, 255, 90))
            screen.blit(hl_surf, hl.topleft)
        tx = r.x + int(15 * scale)
        screen.blit(self.font_surf, (tx, r.centery - self.font_surf.get_height() // 2))
        if self.active and int(time.time() * 2) % 2 == 0:
            ui_rect(screen, (255, 255, 255),
                    pygame.Rect(tx + tw + int(2 * scale), r.centery - int(10 * scale),
                                max(2, int(2 * scale)), int(20 * scale)))
class Slider:
    RANGE_MIN, RANGE_MAX = -9999.5, 9999.5
    def __init__(self, x, y, width, height, init_val):
        self.rect = pygame.Rect(x, y, width, height)
        self.knob_w = 10
        self.knob_h = 26
        self.value = float(init_val)
        self.dragging = False
    def val_to_x(self, val):
        return self.rect.x + ((val - self.RANGE_MIN) / (self.RANGE_MAX - self.RANGE_MIN)) * self.rect.width
    def x_to_val(self, px):
        ratio = max(0.0, min(1.0, (px - self.rect.x) / self.rect.width))
        return self.RANGE_MIN + ratio * (self.RANGE_MAX - self.RANGE_MIN)
    def set_value(self, v):
        self.value = max(self.RANGE_MIN, min(self.RANGE_MAX, float(v)))
    def handle_event(self, event):
        pos = event.pos if event.type in (pygame.MOUSEBUTTONDOWN, pygame.MOUSEMOTION) else \
              (event.x * DESIGN_W, event.y * DESIGN_H) if event.type in (pygame.FINGERDOWN, pygame.FINGERMOTION) else (-1, -1)
        if event.type in (pygame.MOUSEBUTTONDOWN, pygame.FINGERDOWN):
            if self.rect.inflate(20, 20).collidepoint(pos):
                self.dragging = True
                self.value = self.x_to_val(pos[0])
                return True
        elif event.type in (pygame.MOUSEBUTTONUP, pygame.FINGERUP): self.dragging = False
        elif self.dragging and pos[0] != -1:
            self.value = self.x_to_val(pos[0])
            return True
        return False
    def draw(self, screen, blur_surf):
        r = srect(self.rect)
        fill_w = max(0, int(self.val_to_x(self.value) - self.rect.x))
        draw_ios_glass(screen, self.rect, blur_surf, is_pressed=False, is_dark=True)
        if fill_w > 0:
            ui_rect(screen, (255, 255, 255, 200), pygame.Rect(r.x, r.y, int(fill_w * scale), r.height))

        knob_rect = pygame.Rect(r.x + int((self.val_to_x(self.value) - self.rect.x) * scale) - int(self.knob_w * scale)//2,
                                r.centery - int(self.knob_h * scale)//2,
                                max(2, int(self.knob_w * scale)), max(2, int(self.knob_h * scale)))
        ui_rect(screen, (0, 0, 0, 60), knob_rect.move(2, 2))
        ui_rect(screen, (255, 255, 255), knob_rect)
        ui_rect(screen, (150, 150, 150), knob_rect, 1)
# ---------- 锁屏（iPhone 风格密码页，方形控件） ----------
_key_cache = {}
_lock_dim = None
_stats_bg = None       # 数据页背景：把设置页整页模糊后缓存一次
_stats_bg_size = None
_stats_dim = None      # 数据页压暗层（按窗口尺寸缓存）
_lock_dim_hard = None
_lock_box_cache = {}
class LockPadKey:
    def __init__(self, x, y, w, h, label, code, accent=False):
        self.rect = pygame.Rect(x, y, w, h)      # 设计坐标
        self.label, self.code, self.pressed, self.accent = label, code, False, accent
    def hit(self, pos): return self.rect.collidepoint(pos)
    def draw(self, screen):
        r = srect(self.rect)
        key = (r.width, r.height, self.pressed, self.accent)
        s = _key_cache.get(key)
        if s is None:
            s = pygame.Surface((r.width, r.height), pygame.SRCALPHA)
            if self.accent:
                s.fill((10, 132, 255, 170) if self.pressed else (10, 132, 255, 110))
            else:
                s.fill((255, 255, 255, 150) if self.pressed else (255, 255, 255, 46))
            ui_rect(s, (255, 255, 255, 120), s.get_rect(), max(1, px(1.5)))
            if len(_key_cache) > 40: _key_cache.clear()
            _key_cache[key] = s
        screen.blit(s, r.topleft)
        if self.code == "del":
            # 直接渲染字符（用系统符号字体，未命中则回落到中文字体）
            lbl = text_surf(font_del, "⌫", (255, 255, 255))
            screen.blit(lbl, lbl.get_rect(center=(r.centerx, r.centery)))
            return
        lbl = text_surf(font_big if len(self.label) == 1 else font, self.label, (255, 255, 255))
        screen.blit(lbl, lbl.get_rect(center=(r.centerx, r.centery)))
LOCK_TARGET = "7355608"
lock_input = ""
lock_err = 0
# ---------- 失败惩罚（加密写入 AppData，重启无效绕不过） ----------
LOCK_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "SuperWuGuoChicken")
LOCK_FILE = os.path.join(LOCK_DIR, "lock.dat")
_LK_SALT = "SuperWuGuoChicken/7355608/lock-state/v2".encode("utf-8")
def _lk_xor(data):
    k = hashlib.sha256(_LK_SALT).digest()
    kl = len(k)
    return bytes(b ^ k[i % kl] for i, b in enumerate(data))
def load_lock_state():
    try:
        with open(LOCK_FILE, "rb") as f:
            raw = _lk_xor(f.read())
        sig, sep, body = raw.partition(b"|")
        if not sep: return 0, 0.0, 0, 0
        if hashlib.sha256(body + _LK_SALT).hexdigest()[:16].encode() != sig:
            return 0, 0.0, 0, 0          # 被改过就当作没锁
        d = json.loads(body.decode("utf-8"))
        return int(d.get("fails", 0)), float(d.get("until", 0)), int(d.get("pen", 0)), int(d.get("dis", 0))
    except Exception:
        return 0, 0.0, 0, 0
def save_lock_state(fails, until, pen=0, dis=0):
    try:
        os.makedirs(LOCK_DIR, exist_ok=True)
        body = json.dumps({"fails": fails, "until": until, "pen": int(pen), "dis": int(dis)},
                          separators=(",", ":")).encode("utf-8")
        sig = hashlib.sha256(body + _LK_SALT).hexdigest()[:16].encode()
        with open(LOCK_FILE, "wb") as f:
            f.write(_lk_xor(sig + b"|" + body))
    except Exception:
        pass
lock_fails, lock_until, lock_pen, lock_dis = load_lock_state()
# ---------- 历史最高成绩（同样是加密 + 验签的二进制文件） ----------
STAT_FILE = os.path.join(LOCK_DIR, "data.dat")
def load_stats():
    try:
        with open(STAT_FILE, "rb") as f:
            raw = _lk_xor(f.read())
        sig, sep, body = raw.partition(b"|")
        if not sep: return {"s0": 0, "s1": 0, "c1": 0, "s2": 0, "c2": 0}
        if hashlib.sha256(body + _LK_SALT).hexdigest()[:16].encode() != sig:
            return {"s0": 0, "s1": 0, "c1": 0, "s2": 0, "c2": 0}
        d = json.loads(body.decode("utf-8"))
        return {"s0": int(d.get("s0", 0)), "s1": int(d.get("s1", 0)), "c1": int(d.get("c1", 0)),
                "s2": int(d.get("s2", 0)), "c2": int(d.get("c2", 0))}
    except Exception:
        return {"s0": 0, "s1": 0, "c1": 0, "s2": 0, "c2": 0}
def save_stats(d):
    try:
        os.makedirs(LOCK_DIR, exist_ok=True)
        body = json.dumps({k: int(d.get(k, 0)) for k in ("s0", "s1", "c1", "s2", "c2")},
                          separators=(",", ":")).encode("utf-8")
        sig = hashlib.sha256(body + _LK_SALT).hexdigest()[:16].encode()
        with open(STAT_FILE, "wb") as f:
            f.write(_lk_xor(sig + b"|" + body))
    except Exception:
        pass
best_stats = load_stats()
run_cheated = False          # 这一局只要开过任何一个作弊开关，整局成绩作废
def mark_cheated():
    global run_cheated
    run_cheated = True
def record_run():
    # 每局结束调用：分数三个模式都记，金币只在模式二/三记；超出记录才写盘
    # 开过作弊的局一律不记（哪怕中途关掉了）
    if run_cheated: return
    mode = game_state["game_mode"]
    score = int(game_state["score"])
    coins = int(game_state["coin_cnt"])
    keys = {0: [("s0", score)],
            1: [("s1", score), ("c1", coins)],
            2: [("s2", score), ("c2", coins)]}[mode]
    changed = False
    for k, v in keys:
        if v > best_stats[k]:
            best_stats[k] = v
            changed = True
    if changed: save_stats(best_stats)
f2_held = False
KONAMI = [pygame.K_UP, pygame.K_UP, pygame.K_DOWN, pygame.K_DOWN, pygame.K_LEFT, pygame.K_RIGHT,
          pygame.K_LEFT, pygame.K_RIGHT, pygame.K_b, pygame.K_a]
konami_idx = 0
def lock_penalty(fails):
    # 三档：第5次 1 分钟，第6次 5 分钟，第7次起 停用
    if fails < 5: return 0
    return {5: 60, 6: 300}.get(fails, -1)
lock_pad = []
for _idx, _lab in enumerate(["1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "好"]):
    _code = "ok" if _lab == "好" else (_lab if _lab else "del")
    lock_pad.append(LockPadKey(669 + (_idx % 3) * 206, 552 + (_idx // 3) * 118, 170, 92, _lab, _code, accent=(_code == "ok")))
def lock_submit():
    global lock_input, lock_err, lock_fails, lock_until, lock_pen, lock_dis
    if lock_input == LOCK_TARGET:
        lock_input = ""; lock_err = 0; lock_fails = 0; lock_until = 0.0; lock_pen = 0; lock_dis = 0
        save_lock_state(0, 0, 0, 0)
        return "setup"
    lock_input = ""; lock_err = 24
    lock_fails += 1
    pen = lock_penalty(lock_fails)
    if pen > 0:
        lock_until = time.time() + pen; lock_pen = pen; lock_dis = 0
    elif pen < 0:
        lock_until = 0.0; lock_pen = 0; lock_dis = 1      # 游戏已停用
    else:
        lock_until = 0.0; lock_pen = 0; lock_dis = 0
    save_lock_state(lock_fails, lock_until, lock_pen, lock_dis)
    return "lock"
def handle_lock_events(event):
    global lock_input, lock_err, lock_fails, lock_until, lock_pen, lock_dis, f2_held, konami_idx
    if event.type == pygame.KEYDOWN:
        if event.key == pygame.K_F2:
            f2_held = True
            return "lock"
        if event.key in KONAMI:
            # 秘籍：↑↑↓↓←→←→ B A —— 只清掉锁定状态，锁屏本身不跳过
            if event.key == KONAMI[konami_idx]:
                konami_idx += 1
                if konami_idx == len(KONAMI):
                    konami_idx = 0; lock_input = ""; lock_err = 0
                    lock_fails = 0; lock_until = 0.0; lock_pen = 0; lock_dis = 0
                    save_lock_state(0, 0, 0, 0)
                    return "lock"
            else:
                konami_idx = 1 if event.key == KONAMI[0] else 0
    elif event.type == pygame.KEYUP and event.key == pygame.K_F2:
        f2_held = False
        return "lock"
    if lock_dis or lock_until > time.time():   # 惩罚中：除 F2 / 秘籍外全部输入无效
        if event.type in (pygame.MOUSEBUTTONUP, pygame.FINGERUP):
            for k in lock_pad: k.pressed = False
        return "lock"
    if event.type == pygame.KEYDOWN:
        if event.key == pygame.K_BACKSPACE:
            lock_input = lock_input[:-1]
        elif event.key in (pygame.K_RETURN, pygame.K_KP_ENTER):
            if lock_input: return lock_submit()
        elif event.unicode and event.unicode.isprintable() and len(lock_input) < 24:
            lock_input += event.unicode
    pos = event.pos if event.type == pygame.MOUSEBUTTONDOWN else \
          (event.x * DESIGN_W, event.y * DESIGN_H) if event.type == pygame.FINGERDOWN else None
    if pos:
        for k in lock_pad:
            if k.code in ("ok", "del") and not lock_input: continue   # 没输入内容时不出「好」和删除键
            if k.hit(pos):
                k.pressed = True
                if k.code == "del": lock_input = lock_input[:-1]
                elif k.code == "ok": return lock_submit()
                elif len(lock_input) < 24: lock_input += k.code
                break
    if event.type in (pygame.MOUSEBUTTONUP, pygame.FINGERUP):
        for k in lock_pad: k.pressed = False
    return "lock"
def draw_lock_screen(screen, blur_surf):
    # 锁屏压暗层（iPhone 那种夜间感），按窗口尺寸缓存，避免每帧新建整屏透明面
    global _lock_dim, _lock_dim_hard
    if _lock_dim is None or _lock_dim.get_size() != screen.get_size():
        _lock_dim = pygame.Surface(screen.get_size(), pygame.SRCALPHA)
        _lock_dim.fill((0, 0, 0, 30))
        _lock_dim_hard = pygame.Surface(screen.get_size(), pygame.SRCALPHA)
        _lock_dim_hard.fill((0, 0, 0, 95))
    screen.blit(_lock_dim, (0, 0))
    t_time = text_surf(font_lock, time.strftime("%H:%M"), (255, 255, 255))
    screen.blit(t_time, (sx(DESIGN_W // 2) - t_time.get_width() // 2, sy(52)))
    t_date = time.strftime("%m月%d日") + " 星期" + "日一二三四五六"[int(time.strftime("%w"))]
    d_surf = text_surf(font_small, t_date, (235, 235, 235))
    screen.blit(d_surf, (sx(DESIGN_W // 2) - d_surf.get_width() // 2, sy(178)))
    remain = int(lock_until - time.time()) + 1
    if lock_dis or remain > 0:
        # 惩罚锁定态：再叠一层压暗；默认不显示剩余时间，按住 F2 才露出来
        screen.blit(_lock_dim_hard, (0, 0))
        if lock_dis:
            t_big = text_surf(font_lock, "游戏已停用", (215, 215, 215))
            screen.blit(t_big, (sx(DESIGN_W // 2) - t_big.get_width() // 2, sy(380)))
            return
        tip = text_surf(font, "游戏已锁定", (190, 190, 190))
        screen.blit(tip, (sx(DESIGN_W // 2) - tip.get_width() // 2, sy(300)))
        if f2_held:
            # 按住 F2：实时倒计时
            big = f"{remain // 60}:{remain % 60:02d}" if remain >= 60 else f"{remain} 秒"
            t_big = text_surf(font_lock, big, (255, 255, 255))
        else:
            # 松开 F2：只显示这次被锁的时长（静态，不倒数）
            p = lock_pen or remain
            big = "1 小时" if p >= 3600 else (f"{p // 60} 分钟" if p >= 60 else f"{p} 秒")
            t_big = text_surf(font_lock, big, (215, 215, 215))
        screen.blit(t_big, (sx(DESIGN_W // 2) - t_big.get_width() // 2, sy(430)))
        return
    # 纯文字锁屏：不放图标，只留标题（与设置页「只要文字」的调子一致）
    title = text_surf(font, "输入密码", (255, 255, 255))
    screen.blit(title, (sx(DESIGN_W // 2) - title.get_width() // 2, sy(300)))
    shake = int(math.sin(lock_err * 0.9) * 12) if lock_err > 0 else 0
    field = pygame.Rect(669 + shake, 412, 582, 92)
    br = srect(field)
    bsurf = _lock_box_cache.get(br.size)
    if bsurf is None:
        bsurf = pygame.Surface(br.size, pygame.SRCALPHA)
        bsurf.fill((255, 255, 255, 42))
        _lock_box_cache[br.size] = bsurf
    screen.blit(bsurf, br.topleft)
    ui_rect(screen, (255, 90, 90) if lock_err > 0 else (255, 255, 255, 110), br, max(1, px(1.6)))
    inner_l = br.x + px(22)
    star = text_surf(font_big, "*", (255, 255, 255))
    ink = star.get_bounding_rect(min_alpha=1)      # 星号墨迹在字形上偏上，按墨迹垂直居中
    step = px(26)                                  # 每个星号的固定步进，等宽不外溢
    limit = br.width - px(60)
    maxn = max(1, limit // step)
    n = len(lock_input)
    skip = max(0, n - maxn)                        # 太长就只显示尾部
    base_y = br.centery - ink.centery
    for i in range(skip, n):
        screen.blit(star, (inner_l - ink.x + (i - skip) * step, base_y))
    if int(time.time() * 2) % 2 == 0:               # 光标闪烁
        caret_x = inner_l + (n - skip) * step + px(3)
        ui_rect(screen, (255, 255, 255),
                pygame.Rect(caret_x, br.centery - px(22), max(2, px(3)), px(44)))
    for k in lock_pad:
        if k.code in ("ok", "del") and not lock_input: continue   # 输入内容后才出现「好」和删除键
        k.draw(screen)
# ---------- 3. 全局状态与配置 ----------
cfg_run = {"obs_speed": 20, "gravity": 1.8, "jump_power": -40.0, "spawn_interval": 90, "max_hp": 5, "hurt_duration": 15, "player_x": 200, "anim_speed": 8, "obs_scale": 1.0, "bg_scroll_speed_scale": 1.0, "score_rate": 0.5, "jump_damping": 0.0, "fall_scale": 1.8, "jitter": 45}
labels_run = ["游戏速度：", "重力：", "跳跃初速度：", "障碍生成间隔：", "最大HP：", "受伤无敌帧：", "玩家X坐标：", "动画速度：", "障碍缩放：", "背景速度：", "得分倍率：", "跳跃空气阻尼：", "下落倍数（蹲下）：", "障碍随机偏移："]
cfg_free = {"obs_speed": 20, "gravity": 1.8, "jump_power": -32.0, "spawn_interval": 100, "max_hp": 5, "hurt_duration": 15, "anim_speed": 8, "obs_scale": 1.0, "bg_scroll_speed_scale": 1.0, "score_rate": 0.5, "jump_damping": 30.0, "fall_scale": 1.8, "move_accel": 1.5, "friction": 0.85, "jitter": 30}
labels_free = ["障碍速度：", "重力：", "跳跃初速度：", "障碍生成间隔：", "最大HP：", "受伤无敌帧：", "动画速度：", "障碍缩放：", "背景速度：", "得分倍率：", "跳跃空气阻尼：", "下落倍数（蹲下）：", "起步加速度：", "水平摩擦力（0急停）：", "障碍随机偏移："]
cfg_mario = {"move_speed": 20, "gravity": 1.8, "jump_power": -34.0, "spawn_interval": 180, "max_hp": 5, "hurt_duration": 45, "anim_speed": 8, "obs_scale": 1.0, "score_rate": 0.5, "jump_damping": 40.0, "fall_scale": 1.8, "move_accel": 2.0, "friction": 0.85, "jitter": 115}
labels_mario = ["移动速度上限：", "重力：", "跳跃初速度：", "障碍生成间隔：", "最大HP：", "受伤无敌帧：", "动画速度：", "障碍缩放：", "得分倍率：", "跳跃空气阻尼：", "下落倍数（蹲下）：", "起步加速度：", "水平摩擦力（0急停）：", "障碍随机偏移："]
default_cfgs = [cfg_run.copy(), cfg_free.copy(), cfg_mario.copy()]
input_boxes, sliders, box_labels, reset_button_rects, reset_glass_btns = [], [], [], [], []
def build_ui_for_mode(mode):
    global input_boxes, sliders, box_labels, reset_button_rects, reset_glass_btns
    cfg = [cfg_run, cfg_free, cfg_mario][mode]
    box_labels = [labels_run, labels_free, labels_mario][mode]

    input_boxes = []
    sliders = []
    for k, v in cfg.items():
        # 扩大整数参数判定范围
        is_int = k in ["spawn_interval", "max_hp", "hurt_duration", "jitter", "anim_speed", "player_x"]
        # 输入框宽度设为 280，字体设为 26
        box = InputBox(0, 0, 280, 44, str(v), is_int)
        input_boxes.append(box)
        sliders.append(Slider(0, 0, 450, 22, float(v)))

    reset_button_rects = [None] * len(cfg)
    reset_glass_btns = [GlassButton((0,0,10,10), "重置", font_size=font_reset, is_dark=True) for _ in range(len(cfg))]
# --- 按钮矩阵 ---
start_btn_rect = pygame.Rect(0, 0, 360, 80)
all_reset_btn_rect = pygame.Rect(0, 0, 360, 80)
exit_btn_rect = pygame.Rect(0, 0, 360, 80)
control_btn_rect = pygame.Rect(0, 0, 360, 80)
button_rect = pygame.Rect(DESIGN_W//2 - 130, DESIGN_H//2 + 100, 260, 90)
pause_button_rect = pygame.Rect(DESIGN_W - 160, 20, 140, 60)
mode_btn_rect = pygame.Rect(DESIGN_W - 350, 40, 300, 60)
resume_btn_rect = pygame.Rect(DESIGN_W//2 - 150, DESIGN_H//2 - 120, 300, 80)
pause_control_btn_rect = pygame.Rect(DESIGN_W//2 - 150, DESIGN_H//2 - 20, 300, 80)
pause_quit_btn_rect = pygame.Rect(DESIGN_W//2 - 150, DESIGN_H//2 + 80, 300, 80)
back_btn_rect = pygame.Rect(DESIGN_W//2 - 150, DESIGN_H//2 + 180, 300, 80)
kbd_btn_rect = pygame.Rect(DESIGN_W//2 - 350, DESIGN_H//2 - 50, 300, 100)
tch_btn_rect = pygame.Rect(DESIGN_W//2 + 50, DESIGN_H//2 - 50, 300, 100)
left_btn_rect = pygame.Rect(60, DESIGN_H - 180, 120, 120)
right_btn_rect = pygame.Rect(210, DESIGN_H - 180, 120, 120)
jump_btn_rect = pygame.Rect(DESIGN_W - 200, DESIGN_H - 370, 150, 150)
crouch_btn_rect = pygame.Rect(DESIGN_W - 200, DESIGN_H - 200, 150, 150)
btn_start = GlassButton(start_btn_rect, "开始游戏", font_size=font_big, is_dark=False)
btn_reset_all = GlassButton(all_reset_btn_rect, "一键全部重置", font_size=font, is_dark=False)
btn_exit = GlassButton(exit_btn_rect, "退出游戏", font_size=font, is_dark=False)
btn_mode = GlassButton(mode_btn_rect, "跑步鸡", font_size=font, is_dark=False)
stats_btn_rect = pygame.Rect(DESIGN_W - 350, 112, 300, 60)
btn_stats = GlassButton(stats_btn_rect, "游戏数据", font_size=font, is_dark=False)
stats_close_rect = pygame.Rect(DESIGN_W // 2 - 180, 830, 360, 80)
btn_stats_close = GlassButton(stats_close_rect, "关闭", font_size=font, is_dark=False)
stats_open = False
btn_retry = GlassButton(button_rect, "重试", font_size=font, is_dark=True)
btn_retry.glass_alpha = 225
btn_pause = GlassButton(pause_button_rect, "暂停", font_size=font, is_dark=False)
btn_pause.glass_alpha = 178
btn_left = GlassButton(left_btn_rect, "◀", font_size=font_big, is_dark=False)
btn_right = GlassButton(right_btn_rect, "▶", font_size=font_big, is_dark=False)
btn_jump = GlassButton(jump_btn_rect, "▲", font_size=font_big, is_dark=False)
btn_crouch = GlassButton(crouch_btn_rect, "▼", font_size=font_big, is_dark=False)
btn_control = GlassButton(control_btn_rect, "设置", font_size=font, is_dark=False)
btn_resume = GlassButton(resume_btn_rect, "继续游戏", font_size=font, is_dark=True)
btn_resume.glass_alpha = 225
btn_pause_control = GlassButton(pause_control_btn_rect, "设置", font_size=font, is_dark=True)
btn_pause_control.glass_alpha = 225
btn_quit = GlassButton(pause_quit_btn_rect, "返回主菜单", font_size=font, is_dark=True)
btn_quit.glass_alpha = 225
btn_back = GlassButton(back_btn_rect, "返回", font_size=font, is_dark=True)
# 游戏内控件加重模糊：文字压在更"糊"的玻璃上，对比度更高
def apply_blur_level():
    """把当前模糊档位乘到控件半径上（改档后必须重算，页面底图缓存也要作废）"""
    m = blur_mul()
    for _gb in (btn_left, btn_right, btn_jump, btn_crouch):
        _gb.blur_strength = max(2.2, 3.0 * scale)
        _gb.blur_px_value = max(6, int(round(BLUR_CTRL_PX * m)))
    btn_pause.blur_px_value = max(8, int(round(BLUR_PAUSE_PX * m)))
def refresh_blur_cache():
    global ui_dirty
    _gpu_blur_cache.clear()
    _blur_cache["surf"], _blur_cache["key"] = None, None
    _frost_gen[0] += 1
    ui_dirty = True
apply_blur_level()
btn_kbd = GlassButton(kbd_btn_rect, "键盘操控", font_size=font_big, is_dark=False)
btn_tch = GlassButton(tch_btn_rect, "触屏操控", font_size=font_big, is_dark=False)

# ---------- 设置页：左栏项目、右栏内容 ----------
SETTINGS_TABS = ["控制设置", "模糊设置", "参数设置", "作弊选项", "渲染器"]
settings_tab = 0
nav_btns = [GlassButton(pygame.Rect(30, 190 + i * 104, 500, 92), t, font_size=font, is_dark=True)
            for i, t in enumerate(SETTINGS_TABS)]
kbd_opt_rect = pygame.Rect(1010, 330, 380, 110)
tch_opt_rect = pygame.Rect(1420, 330, 380, 110)
blur_opt_btns = [GlassButton(pygame.Rect(660 + i * 400, 330, 380, 110),
                             BLUR_LEVELS[i][0], font_size=font_big, is_dark=True)
                 for i in range(len(BLUR_LEVELS))]
btn_nc = GlassButton(pygame.Rect(1560, 250, 240, 96), "关", font_size=font, is_dark=True)
btn_nd = GlassButton(pygame.Rect(1560, 415, 240, 96), "关", font_size=font, is_dark=True)
btn_reset_ingame_all = GlassButton(pygame.Rect(1600, 96, 260, 64), "全部重置", font_size=font, is_dark=True)
# 渲染器页：三条后端选项 + 重启按钮
RENDER_CHOICES = [("OpenGL", "gl"), ("Vulkan", "vk"), ("软件加速", "soft")]
RENDER_DESCS = [
    "OpenGL：支持更多画面效果，硬件加速",
    "Vulkan：性能相较于OpenGL更优",
    "软件加速：基础加速，基础画面效果",
]
render_opt_btns = [GlassButton(pygame.Rect(660 + i * 400, 292, 380, 104), RENDER_CHOICES[i][0],
                               font_size=font_big, is_dark=True) for i in range(len(RENDER_CHOICES))]
btn_relaunch = GlassButton(pygame.Rect(1460, 880, 400, 80), "重启游戏", font_size=font, is_dark=True)
btn_cancel = GlassButton(pygame.Rect(1020, 880, 400, 80), "取消修改", font_size=font, is_dark=True)
render_pending = None          # 选好但还没生效的后端键
toast_text = ""                # 页面底部提示：3 秒自己消失
toast_until = 0
def show_toast(msg, ms=3000):
    global toast_text, toast_until
    toast_text, toast_until = msg, pygame.time.get_ticks() + ms
def toast_alive():
    return toast_text and pygame.time.get_ticks() < toast_until
game_state = {
    "game_mode": 0, "input_mode": 0, # 0 = 键盘, 1 = 触屏
    "score": 0, "hp": 5, "coin_cnt": 0,
    "player_x": 200, "player_y": GROUND_Y, "player_vx": 0, "player_vy": 0,
    "on_ground": True, "facing_right": True, "is_moving": False,
    "anim_timer": 0, "anim_index": 0, "hurt_timer": 0, "player_dead_angle": 0,
    "touch_left": False, "touch_right": False, "jump_held": False,
    "crouch_held": False, "crouching": False, "crouch_jump": False, "fall_scale": 1.8,
    "bg_x": 0, "cam_x": 0, "spawn_timer": 0, "obs_list": [], "coin_list": [], "popups": [],
    "no_collision": False, "no_damage": False      # 设置页开关：无碰撞体积 / 无敌不掉血
}
build_ui_for_mode(0)
blur_engine = BlurSurface(*WIN_SIZE(), blur_strength=max(2.0, 6.5 * scale))
def get_tinted_blur(source, tint_key, tint_color):
    # 静止界面（设置/暂停/死亡/操作选择）整屏模糊+压暗只在有操作时重算，避免每帧两次全画布缩放
    global ui_dirty
    if GPU_OK:
        # GPU 路径：每页只在进入时生成一次模糊纹理，之后每帧只贴图
        key = (tint_key, WIN_SIZE())
        ent = _gpu_blur_cache.get(key)
        if ent is None:
            ent = build_gpu_blur(tint_key, tint_color)
            _gpu_blur_cache[key] = ent
            ui_dirty = False
        return ent
    if ui_dirty or _blur_cache.get("surf") is None or _blur_cache.get("key") != tint_key:
        w, h = WIN_SIZE()
        cw, ch = max(1, draw_w), max(1, draw_h)
        clip = pygame.Rect(int(off_x), int(off_y), cw, ch).clip(source.get_rect())
        s = pygame.Surface((w, h))
        s.fill((0, 0, 0))
        if clip.width > 4 and clip.height > 4:
            st = blur_engine.blur_strength
            r = max(1, int(round(page_blur_px())))
            # 同样往画布里缩 r 再模糊，画布外的黑边不参与取样
            inner = clip.inflate(-2 * r, -2 * r)
            if inner.width <= 0 or inner.height <= 0: inner = clip
            small = pygame.transform.smoothscale(
                source.subsurface(inner),
                (max(1, int(inner.width / st)), max(1, int(inner.height / st))))
            part = pygame.transform.smoothscale(small, (clip.width, clip.height))
            part.fill(tint_color, special_flags=pygame.BLEND_RGBA_ADD)
            s.blit(part, clip.topleft)
        _blur_cache["surf"], _blur_cache["key"] = s, tint_key
        _blur_gen[0] += 1          # 底图变了，玻璃块缓存全部失效
        ui_dirty = False
    return _blur_cache["surf"]
# ---------- 4. 物理与游戏逻辑 ----------
_pressed = None
def keys_now():
    return _pressed if _pressed is not None else pygame.key.get_pressed()
def apply_gravity_and_floor():
    g = game_state["gravity"]
    if game_state["jump_held"] and game_state["player_vy"] < 0:
        g *= (1 - game_state["jump_damping"] * 0.01)
    # 蹲下加速下落：蹲跳（起跳时已蹲）到顶点生效；空中才触发蹲则立即生效；松开随时回归普通重力
    if game_state["crouching"] and (game_state["player_vy"] >= 0 or not game_state["crouch_jump"]):
        g *= game_state["fall_scale"]
    game_state["player_vy"] += g
    game_state["player_y"] += game_state["player_vy"]
    if game_state["player_y"] >= GROUND_Y:
        game_state["player_y"] = GROUND_Y
        game_state["player_vy"] = 0
        game_state["on_ground"] = True
    else: game_state["on_ground"] = False
def update_horizontal_movement(accel_key, friction_key, speed_limit):
    if game_state["input_mode"] == 0:
        keys = keys_now()
        left = keys[pygame.K_a]
        right = keys[pygame.K_d]
    else:
        left = game_state["touch_left"]
        right = game_state["touch_right"]
    accel, friction = game_state[accel_key], game_state[friction_key]

    if left:
        game_state["player_vx"] = max(game_state["player_vx"] - accel, -speed_limit)
        game_state["facing_right"] = False
    elif right:
        game_state["player_vx"] = min(game_state["player_vx"] + accel, speed_limit)
        game_state["facing_right"] = True
    else:
        game_state["player_vx"] *= friction
        if abs(game_state["player_vx"]) < 0.2: game_state["player_vx"] = 0
    return left or right or abs(game_state["player_vx"]) > 0
def update_crouch_state():
    if game_state["input_mode"] == 0:
        keys = keys_now()
        game_state["crouch_held"] = bool(keys[pygame.K_LSHIFT] or keys[pygame.K_RSHIFT])
    game_state["crouching"] = game_state["crouch_held"]
def is_crouch_locked():
    return game_state["crouching"]
_pmask_key, _pmask_val = None, None
def get_player_mask():
    # 结果按状态缓存：同帧内两次碰撞检测不必重复选表
    global _pmask_key, _pmask_val
    crouch = is_crouch_locked()
    hurt = game_state["hurt_timer"] > 0 or state == "gameover"
    k = (crouch, hurt, game_state["facing_right"], game_state["anim_index"])
    if k != _pmask_key:
        if crouch:
            if hurt: v = hurt_sit_mask_right if game_state["facing_right"] else hurt_sit_mask_left
            else: v = sit_mask_right if game_state["facing_right"] else sit_mask_left
        elif hurt:
            v = (hurt_masks_right if game_state["facing_right"] else hurt_masks_left)[game_state["anim_index"]]
        else:
            v = (player_masks_right if game_state["facing_right"] else player_masks_left)[game_state["anim_index"]]
        _pmask_key, _pmask_val = k, v
    return _pmask_val
def do_jump():
    # 记录起跳瞬间是否蹲着：蹲着起跳=到顶点才用下落倍数；空中才按蹲=立即用
    if game_state["input_mode"] == 0:
        keys = keys_now()
        crouch_now = bool(keys[pygame.K_LSHIFT] or keys[pygame.K_RSHIFT])
    else:
        crouch_now = bool(game_state["crouch_held"])
    game_state["crouch_jump"] = crouch_now
    if game_state["on_ground"]:
        game_state["player_vy"] = game_state["jump_power"]
        game_state["on_ground"] = False
_obs_cache = {}
def spawn_obstacle(is_scrolling=False):
    pick = random.choice(obs_pool)
    key = (id(pick), game_state["obs_scale"])
    ent = _obs_cache.get(key)
    if ent is None:
        ow, oh = int(pick.get_width() * game_state["obs_scale"]), int(pick.get_height() * game_state["obs_scale"])
        s = pygame.transform.scale(pick, (ow, oh))
        ent = (s, pygame.mask.from_surface(s), ow, oh)   # 缩放图与遮罩只生成一次（遮罩构建最费时）
        _obs_cache[key] = ent
    scaled_obs, obs_mask, ow, oh = ent

    if is_scrolling:
        last_x = max((o["x"] for o in game_state["obs_list"]), default=game_state["cam_x"] + DESIGN_W)
        spawn_x = max(game_state["cam_x"] + DESIGN_W + 300, last_x) + random.randint(0, 500)
    else:
        spawn_x = DESIGN_W

    game_state["obs_list"].append({"x": spawn_x, "y": DESIGN_H - oh, "w": ow, "h": oh, "img": scaled_obs, "mask": obs_mask})
def process_collisions():
    cur_mask = get_player_mask()
    cam_offset = game_state["cam_x"] if game_state["game_mode"] == 2 else 0
    px, py = game_state["player_x"] - cam_offset, game_state["player_y"]
    pmw, pmh = cur_mask.get_size()

    for i in range(len(game_state["obs_list"]) - 1, -1, -1):
        obs = game_state["obs_list"][i]
        ox, oy, ow, oh = obs["x"] - cam_offset, obs["y"], obs["w"], obs["h"]
        if ox + ow < 0:
            game_state["obs_list"].pop(i)
            continue
        # 先做矩形粗筛，远离玩家就不做逐像素遮罩检测
        if ox > px + pmw or ox + ow < px or oy > py + pmh or oy + oh < py: continue
        if cur_mask.overlap(obs["mask"], (int(ox - px), int(oy - py))):
            if game_state.get("no_collision"):
                continue                       # 开了「无碰撞体积」：直接穿过去，障碍不消失
            game_state["obs_list"].pop(i)
            if game_state.get("no_damage"):
                continue                       # 开了「无敌不掉血」：撞掉但血不动
            game_state["hp"] -= 1
            game_state["hurt_timer"] = int(game_state["hurt_duration"])
            if hit_sounds: random.choice(hit_sounds).play()
            if game_state["hp"] <= 0: return False
    return True
def process_coin_collisions():
    cur_mask = get_player_mask()
    cam_offset = game_state["cam_x"] if game_state["game_mode"] == 2 else 0
    px, py = game_state["player_x"] - cam_offset, game_state["player_y"]
    pw = player_frames_right[0].get_width()
    pmw, pmh = cur_mask.get_size()
    cw, ch = coin_mask.get_size()

    for i in range(len(game_state["coin_list"]) - 1, -1, -1):
        c = game_state["coin_list"][i]
        if c["x"] < game_state["cam_x"] - 100:
            game_state["coin_list"].pop(i)
            continue
        cx, cy = c["x"] - cam_offset, c["y"]
        if cx > px + pmw or cx + cw < px or cy > py + pmh or cy + ch < py: continue
        if cur_mask.overlap(coin_mask, (int(cx - px), int(cy - py))):
            game_state["coin_list"].pop(i)
            amount = random.randint(5, 100)
            game_state["score"] += amount
            game_state["coin_cnt"] += 1
            game_state["popups"].append({"x": int(px + pw / 2), "y": int(py), "text": f"+{amount}", "t": 0})
            if hit_sounds: random.choice(hit_sounds).play()
def reset_game():
    global run_cheated
    # 开新一局：开局时作弊开关仍亮着也算开过作弊，整局成绩不记
    run_cheated = bool(game_state.get("no_collision") or game_state.get("no_damage"))
    def get_val(idx, defv, is_int=False):
        try:
            v_str = input_boxes[idx].text.strip()
            if not v_str or v_str in (".", "-", "-."): return defv
            v = max(-9999.5, min(9999.5, float(v_str)))
            return round(v) if is_int else v
        except Exception: return defv
    cfg = [cfg_run, cfg_free, cfg_mario][game_state["game_mode"]]
    for i, key in enumerate(cfg.keys()):
        is_int = key in ["spawn_interval", "max_hp", "hurt_duration", "jitter", "anim_speed", "player_x"]
        v = get_val(i, cfg[key], is_int)
        game_state[key] = v
        cfg[key] = v

    px = 200 if game_state["game_mode"] == 0 else (400 if game_state["game_mode"] == 1 else 200)
    game_state.update({
        "player_x": px, "player_y": GROUND_Y, "player_vy": 0, "player_vx": 0, "on_ground": True,
        "facing_right": True, "spawn_timer": 0, "bg_x": 0, "cam_x": 0, "anim_timer": 0, "anim_index": 0,
        "score": 0, "hp": game_state["max_hp"], "hurt_timer": 0, "player_dead_angle": 0,
        "touch_left": False, "touch_right": False, "jump_held": False, "is_moving": False, "coin_cnt": 0,
        "crouch_held": False, "crouching": False, "crouch_jump": False
    })
    game_state["obs_list"].clear(); game_state["coin_list"].clear(); game_state["popups"].clear()
def draw_player(screen):
    draw_x = world_x(game_state["player_x"])
    is_hurt = game_state["hurt_timer"] > 0 or state == "gameover"
    if is_crouch_locked():
        # 蹲下锁死 sit.png（红温 / 死亡同样使用蹲姿）
        draw_img = (hurt_sit_right if game_state["facing_right"] else hurt_sit_left) if is_hurt else (sit_img_right if game_state["facing_right"] else sit_img_left)
    else:
        frames = (hurt_frames_right if game_state["facing_right"] else hurt_frames_left) if is_hurt else (player_frames_right if game_state["facing_right"] else player_frames_left)
        draw_img = frames[game_state["anim_index"]]

    if state == "gameover":
        draw_img = pygame.transform.rotate(draw_img, game_state["player_dead_angle"])
        img = ds(draw_img)
        screen.blit(img, img.get_rect(center=(draw_x + px(draw_img.get_width()) // 2,
                                              off_y + px(game_state["player_y"] + draw_img.get_height() // 2))))
    else: screen.blit(ds(draw_img), (draw_x, off_y + px(game_state["player_y"])))
# ---------- 5. 事件处理 ----------
def handle_setup_events(event):
    global stats_open, _stats_bg
    if stats_open:
        # 数据面板打开时独占输入，只能按「关闭」或 ESC 退出
        if event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE:
            stats_open = False
            return "setup"
        if btn_stats_close.handle_event(event):
            stats_open = False
        return "setup"
    cfg = [cfg_run, cfg_free, cfg_mario][game_state["game_mode"]]

    for i, box in enumerate(input_boxes):
        if box.handle_event(event):
            try:
                sliders[i].set_value(float(box.text))
                cfg[list(cfg.keys())[i]] = float(box.text)
            except ValueError: pass

    for i, slider in enumerate(sliders):
        if slider.handle_event(event):
            is_int = input_boxes[i].is_int
            input_boxes[i].text = str(round(slider.value)) if is_int else f"{slider.value:.2f}"
            cfg[list(cfg.keys())[i]] = slider.value

    if btn_stats.handle_event(event):
        stats_open = True
        _stats_bg = None          # 打开时重拍一张设置页模糊版当背景
        return "setup"
    if btn_mode.handle_event(event):
        game_state["game_mode"] = (game_state["game_mode"] + 1) % 3
        build_ui_for_mode(game_state["game_mode"])
        btn_mode.text = ["跑步鸡", "滑步鸡", "吴国鸡"][game_state["game_mode"]]
        btn_mode.font_surf = btn_mode.font.render(btn_mode.text, True, (255, 255, 255))
        return "setup"
    if btn_start.handle_event(event): reset_game(); return "game"
    elif btn_reset_all.handle_event(event):
        default_cfg = default_cfgs[game_state["game_mode"]]
        cfg.clear()
        cfg.update(default_cfg.copy())
        build_ui_for_mode(game_state["game_mode"])

    elif btn_control.handle_event(event):
        global prev_state
        prev_state = "setup"
        return "control_settings"
    elif btn_exit.handle_event(event): pygame.event.post(pygame.event.Event(pygame.QUIT))
    else:
        for idx, btn in enumerate(reset_glass_btns):
            if btn.handle_event(event):
                default_v = default_cfgs[game_state["game_mode"]][list(cfg.keys())[idx]]
                input_boxes[idx].text = str(default_v)
                sliders[idx].set_value(float(default_v))
                cfg[list(cfg.keys())[idx]] = default_v
                break
    return "setup"
def handle_game_events(event):
    if event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE: return "paused"

    if event.type == pygame.MOUSEBUTTONDOWN and event.button == 1:
        if pause_button_rect.collidepoint(event.pos): return "paused"
        if game_state["input_mode"] == 1:
            # 三种模式统一：显式跳/蹲按钮（模式一不再「点哪都跳」）
            if left_btn_rect.collidepoint(event.pos) and game_state["game_mode"] != 0: game_state["touch_left"] = True
            elif right_btn_rect.collidepoint(event.pos) and game_state["game_mode"] != 0: game_state["touch_right"] = True
            elif jump_btn_rect.collidepoint(event.pos):
                game_state["jump_held"] = True
                do_jump()
            elif crouch_btn_rect.collidepoint(event.pos):
                game_state["crouch_held"] = True
                btn_crouch.pressed = True
        elif game_state["game_mode"] == 0:
            do_jump()

    if event.type == pygame.FINGERDOWN:
        pos = (event.x * DESIGN_W, event.y * DESIGN_H)
        if pause_button_rect.collidepoint(pos): return "paused"
        if game_state["input_mode"] == 1:
            if left_btn_rect.collidepoint(pos) and game_state["game_mode"] != 0: game_state["touch_left"] = True
            elif right_btn_rect.collidepoint(pos) and game_state["game_mode"] != 0: game_state["touch_right"] = True
            elif jump_btn_rect.collidepoint(pos):
                game_state["jump_held"] = True
                do_jump()
            elif crouch_btn_rect.collidepoint(pos):
                game_state["crouch_held"] = True
                btn_crouch.pressed = True
        elif game_state["game_mode"] == 0:
            do_jump()
    if event.type in (pygame.MOUSEBUTTONUP, pygame.FINGERUP):
        game_state["touch_left"] = False; game_state["touch_right"] = False
        game_state["crouch_held"] = False
        btn_crouch.pressed = False
        if game_state["game_mode"] != 0:
            game_state["jump_held"] = False
            if game_state["player_vy"] < 0: game_state["player_vy"] *= 0.5
    if event.type == pygame.KEYDOWN and event.key in (pygame.K_SPACE, pygame.K_w):
        if game_state["game_mode"] != 0: game_state["jump_held"] = True
        do_jump()
    if event.type == pygame.KEYUP and event.key in (pygame.K_SPACE, pygame.K_w) and game_state["game_mode"] != 0:
        game_state["jump_held"] = False
        if game_state["player_vy"] < 0: game_state["player_vy"] *= 0.5
    return "game"
def handle_paused_events(event):
    if event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE: return "game"
    if btn_resume.handle_event(event): return "game"
    if btn_pause_control.handle_event(event):
        global prev_state
        prev_state = "paused"
        return "control_settings"
    if btn_quit.handle_event(event):
        return "setup"
    return "paused"
def handle_gameover_events(event):
    if btn_retry.handle_event(event): return "setup"
    return "gameover"
def sync_cfg_into_state():
    """游戏内改参数：立刻推到 game_state，不用重开一局"""
    cfg = [cfg_run, cfg_free, cfg_mario][game_state["game_mode"]]
    for k, v in cfg.items():
        if k in game_state:
            game_state[k] = v

def handle_control_events(event):
    global prev_state, settings_tab, blur_level, _stats_bg, render_pending
    if event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE:
        return "paused" if prev_state == "paused" else "setup"
    if btn_back.handle_event(event):
        return "paused" if prev_state == "paused" else "setup"

    for i, b in enumerate(nav_btns):
        if b.handle_event(event):
            settings_tab = i
            return "control_settings"

    tab = SETTINGS_TABS[settings_tab]
    in_game = prev_state == "paused"
    if tab == "控制设置":
        if btn_kbd.handle_event(event): game_state["input_mode"] = 0
        if btn_tch.handle_event(event): game_state["input_mode"] = 1
    elif tab == "模糊设置":
        if current_backend_key() == "soft":
            return "control_settings"       # 软件加速没有 GPU 模糊，选项只读
        for i, b in enumerate(blur_opt_btns):
            if b.handle_event(event):
                blur_level = i
                apply_blur_level(); refresh_blur_cache()
                _stats_bg = None
                return "control_settings"
    elif tab == "参数设置" and in_game:
        cfg = [cfg_run, cfg_free, cfg_mario][game_state["game_mode"]]
        keys = list(cfg.keys())
        for i, box in enumerate(input_boxes):
            if box.handle_event(event):
                try:
                    sliders[i].set_value(float(box.text))
                    cfg[keys[i]] = float(box.text)
                    sync_cfg_into_state()
                except ValueError: pass
        for i, slider in enumerate(sliders):
            if slider.handle_event(event):
                is_int = input_boxes[i].is_int
                input_boxes[i].text = str(round(slider.value)) if is_int else f"{slider.value:.2f}"
                cfg[keys[i]] = slider.value
                sync_cfg_into_state()
        for idx, rb in enumerate(reset_glass_btns):
            if rb.handle_event(event):
                dv = default_cfgs[game_state["game_mode"]][keys[idx]]
                input_boxes[idx].text = str(dv)
                sliders[idx].set_value(float(dv))
                cfg[keys[idx]] = dv
                sync_cfg_into_state()
        if btn_reset_ingame_all.handle_event(event):
            dfc = default_cfgs[game_state["game_mode"]]
            for idx, k in enumerate(keys):
                dv = dfc[k]
                input_boxes[idx].text = str(dv)
                sliders[idx].set_value(float(dv))
                cfg[k] = dv
            sync_cfg_into_state()
    elif tab == "渲染器":
        for i, b in enumerate(render_opt_btns):
            if b.handle_event(event):
                name, key = RENDER_CHOICES[i]
                cur = current_backend_key()
                avail = {"gl": OPENGL_OK, "vk": False, "soft": True}.get(key, False)
                if not avail:
                    show_toast(f"{name} 与当前系统不兼容")
                elif key == cur or (key == "gl" and cur == "gpu"):
                    pass          # 已经在跑这个后端：什么都不弹
                else:
                    save_render_pref(key)
                    render_pending = key
                    show_toast(f"已更改为{name}，重启游戏后生效")
                return "control_settings"
        if render_pending:
            if btn_cancel.handle_event(event):
                # 取消 = 把配置文件退回当前正在跑的后端，待生效清空
                save_render_pref("gl" if current_backend_key() in ("gl", "gpu") else "soft")
                render_pending = None
                return "control_settings"
            if btn_relaunch.handle_event(event):
                relaunch_game()
                return "control_settings"
    elif tab == "作弊选项":
        if btn_nc.handle_event(event):
            game_state["no_collision"] = not game_state["no_collision"]
            if game_state["no_collision"]: mark_cheated()
        if btn_nd.handle_event(event):
            game_state["no_damage"] = not game_state["no_damage"]
            if game_state["no_damage"]: mark_cheated()
    return "control_settings"
event_handlers = {
    "lock": handle_lock_events,
    "setup": handle_setup_events,
    "game": handle_game_events,
    "paused": handle_paused_events,
    "gameover": handle_gameover_events,
    "control_settings": handle_control_events
}
# ---------- 6. 主循环 ----------
def refresh_ui_scale():
    # 窗口尺寸变化后：重建字体、清空缩放/文字/模糊缓存、让所有控件换新字号
    global font, font_big, font_small, font_reset, font_input, font_lock, font_del, blur_engine, screen
    screen = gpu_target if GPU_OK else win
    if GPU_OK:
        gpu_target.clear_tex(); _gpu_glass_cache.clear()
    font = font_pt(32); font_big = font_pt(48); font_small = font_pt(24)
    font_reset = font_pt(28); font_input = font_pt(26); font_lock = font_pt(96)
    font_del = get_symbol_font(max(9, int(round(62 * scale))))
    NOMINAL.clear(); NOMINAL.update({id(font):(font,32), id(font_big):(font_big,48), id(font_small):(font_small,24),
                                    id(font_reset):(font_reset,28), id(font_input):(font_input,26), id(font_lock):(font_lock,96)})
    _text_cache.clear(); _scaled_cache.clear(); _glass_cache.clear(); _key_cache.clear(); _lock_box_cache.clear()
    _glass_overlay_cache.clear()
    _btn_glass_cache.clear()
    _blur_cache["surf"], _blur_cache["key"] = None, None
    blur_engine = BlurSurface(*WIN_SIZE(), blur_strength=max(2.0, 6.5 * scale))
    for _gb in (btn_pause, btn_left, btn_right, btn_jump, btn_crouch):
        _gb.blur_strength = max(2.2, 3.0 * scale)
    for obj in list(globals().values()):
        if isinstance(obj, (GlassButton, InputBox)): obj.refresh()
        elif type(obj) in (list, tuple):   # 只认真正的容器，避免误迭代按键状态等元组子类
            for it in obj:
                if isinstance(it, (GlassButton, InputBox)): it.refresh()
    apply_blur_level()        # 缩放变了，控件模糊半径也跟着重算
def snap_aspect(w_, h_):
    """把窗口尺寸吸回设计分辨率比例：只允许等比缩放，画面永远不拉伸"""
    desk_w, desk_h = pygame.display.get_desktop_sizes()[0]
    # Window 没有 fullscreen 属性可查，用「几乎占满屏幕」当全屏判据（留 8px 容差给边框）
    if w_ >= desk_w - 8 or h_ >= desk_h - 8:
        return w_, h_          # 最大化 / 全屏：铺满即可，不强制对齐
    ratio = DESIGN_W / DESIGN_H
    # 以变化更大的那条边为准，另一边按比例算出来
    if abs(w_ - _win_px[0]) >= abs(h_ - _win_px[1]):
        nw = max(640, int(w_))
        nh = max(340, int(round(nw / ratio)))
    else:
        nh = max(340, int(h_))
        nw = max(640, int(round(nh * ratio)))
    return nw, nh
def do_resize(w_, h_):
    # Window 不打 VIDEORESIZE，尺寸变化由主循环比对后调到这里
    global _win_px, state
    _win_px = (w_, h_)
    if GPU_OK:
        gpu_target.clear_tex()
        _gpu_blur_cache.clear()
        scene_flush()
    update_mapping(w_, h_)
    refresh_ui_scale()
    if state == "game":
        state = "paused"

# ---------- 主菜单整页快照 ----------
# 主菜单是唯一一个完全静止的重页（约 335 次绘制/帧：12 组滑条+输入框+重置、6 个底部按钮）。
# 事件驱动下它只在「有点击/改档/缩放/打字」时变，所以整页（模糊底+压暗+全部控件）烘进一张
# 纹理，干净帧只贴一次；脏帧重建一次（约 2ms），换来干净帧 335 次绘制降到 1 次。
_setup_snap_tex = None
_setup_snap_sig = None

def layout_setup_ui():
    """主菜单的几何布局：每帧都跑，保证点击热区和快照里的位置始终一致"""
    total_w = 1250
    start_x = DESIGN_W//2 - total_w//2
    top_y = 210
    bottom_y = DESIGN_H - 140
    num_params = len(input_boxes)
    line_gap = min(60, (bottom_y - top_y) // num_params)
    col1_x = start_x
    col2_x = col1_x + 450 + 25
    col3_x = col2_x + 260 + 25
    for i, box in enumerate(input_boxes):
        center_y = top_y + i * line_gap + 22
        sliders[i].rect.x = col1_x
        sliders[i].rect.width = 450
        sliders[i].rect.centery = center_y
        box.rect.width, box.rect.height = 280, 44
        box.rect.x = col3_x + 44 + 10
        box.rect.centery = center_y
        box.btn_minus.rect.update(col3_x, box.rect.y, 44, box.rect.height)
        box.btn_plus.rect.update(box.rect.right + 10, box.rect.y, 44, box.rect.height)
        if i < len(reset_glass_btns):
            reset_glass_btns[i].rect = pygame.Rect(box.btn_plus.rect.right + 20, box.rect.centery - 16, 64, 32)
    btn_base_y = bottom_y + 20
    group_left_x = DESIGN_W//2 - (360*4 + 25*3)//2
    btn_start.rect.update(group_left_x, btn_base_y, 360, 80)
    btn_reset_all.rect.update(group_left_x + 360 + 25, btn_base_y, 360, 80)
    btn_control.rect.update(group_left_x + (360+25)*2, btn_base_y, 360, 80)
    btn_exit.rect.update(group_left_x + (360+25)*3, btn_base_y, 360, 80)

def draw_setup_ui(screen, blur_surf):
    """主菜单除了背景之外的全部控件（几何由 layout_setup_ui 负责）"""
    ui_fill_alpha(screen, pygame.Rect(0, 0, DESIGN_W, DESIGN_H), (6, 8, 12, 105))

    title = text_surf(font_big, "自定义本局参数 (-9999.5 ~ 9999.5)", (255, 255, 255))
    screen.blit(title, (sx(DESIGN_W//2) - title.get_width()//2, sy(30)))

    info_lines = ["当前游戏模式影响键位和玩法", "【跑步鸡】空格/▲跳跃，Shift/▼下蹲，障碍物向你飞来", "【滑步鸡】A/D 移动，空格/▲跳跃，Shift/▼下蹲",
                  "【吴国鸡】A/D 移动，空格/▲跳跃，Shift/▼下蹲"]
    for idx, line in enumerate(info_lines):
        txt = text_surf(font_small, line, (220, 220, 220))
        screen.blit(txt, (sx(DESIGN_W//2) - txt.get_width()//2, sy(85 + idx*26)))

    col2_x = DESIGN_W//2 - 1250//2 + 450 + 25
    for i, box in enumerate(input_boxes):
        label_surf = text_surf(font, box_labels[i], (255, 255, 255))
        label_rect = label_surf.get_rect(left=sx(col2_x), centery=sy(box.rect.centery))
        sliders[i].draw(screen, blur_surf)
        screen.blit(label_surf, label_rect)
        box.draw(screen, blur_surf)
        if i < len(reset_glass_btns):
            reset_glass_btns[i].draw(screen, blur_surf)

    btn_start.draw(screen, blur_surf)
    btn_reset_all.draw(screen, blur_surf)
    btn_control.draw(screen, blur_surf)
    btn_exit.draw(screen, blur_surf)
    btn_mode.draw(screen, blur_surf)
    btn_stats.draw(screen, blur_surf)

state = "lock"
running = True
prev_state = "setup"
while running:
    clock.tick(60)
    _pressed = pygame.key.get_pressed()   # 每帧取一次，逻辑多处复用
    _frame_no[0] += 1

    for event in pygame.event.get():
        # 只有真正会改变画面内容的事件才让整屏模糊重算；纯鼠标移动（没按键）不重算
        if event.type == pygame.MOUSEMOTION:
            if event.buttons[0] or event.buttons[1] or event.buttons[2]: ui_dirty = True
        else:
            ui_dirty = True
        if event.type == pygame.QUIT or event.type == pygame.WINDOWCLOSE:
            running = False

        # ---------- 窗口事件处理 ----------
        if event.type == pygame.WINDOWFOCUSLOST:
            # 窗口失焦，切暂停，复用已有暂停界面
            if state == "game":
                state = "paused"

        if event.type == pygame.WINDOWRESIZED or event.type == pygame.WINDOWSIZECHANGED:
            pass   # GPU 模式下尺寸变化统一在主循环里检测，避免事件重复处理
        elif event.type == pygame.VIDEORESIZE and not GPU_OK:
            fixed_w, fixed_h = snap_aspect(event.w, event.h)
            if (fixed_w, fixed_h) != (event.w, event.h):
                win = pygame.display.set_mode((fixed_w, fixed_h), pygame.RESIZABLE)
            _win_px = (fixed_w, fixed_h)
            update_mapping(fixed_w, fixed_h)
            refresh_ui_scale()
            # 调整窗口完成，切暂停
            if state == "game":
                state = "paused"

        # 鼠标坐标转换：把窗口屏幕坐标转为虚拟画布坐标，替换event.pos
        if event.type in (pygame.MOUSEBUTTONDOWN, pygame.MOUSEBUTTONUP, pygame.MOUSEMOTION):
            orig_mx, orig_my = event.pos
            virt_mx = (orig_mx - off_x) / scale
            virt_my = (orig_my - off_y) / scale
            event.pos = (virt_mx, virt_my)

        # 分发事件到业务处理器
        new_state = event_handlers[state](event)
        if new_state != state:
            state = new_state
            ui_dirty = True
            if GPU_OK: _gpu_blur_cache.clear()   # 换页了，模糊底图要按新画面重建

    # 逻辑更新
    if state == "game":
        if game_state["hurt_timer"] > 0: game_state["hurt_timer"] -= 1
        for p_ in game_state["popups"][:]:
            p_["y"] -= 1.5; p_["t"] += 1
            if p_["t"] > 45: game_state["popups"].remove(p_)
        mode = game_state["game_mode"]
        if mode == 0:
            game_state["score"] += game_state["score_rate"]
            game_state["bg_x"] = (game_state["bg_x"] - game_state["obs_speed"] * game_state["bg_scroll_speed_scale"]) % -DESIGN_W
            update_crouch_state()                 # 模式一也支持蹲下（Shift / 蹲键）
            apply_gravity_and_floor()
            game_state["anim_timer"] += 1
            if game_state["anim_timer"] >= game_state["anim_speed"]: game_state["anim_timer"] = 0; game_state["anim_index"] = (game_state["anim_index"] + 1) % len(player_frames_right)
            game_state["spawn_timer"] += 1
            if game_state["spawn_timer"] >= game_state["spawn_interval"] + random.randint(-int(game_state["jitter"]), int(game_state["jitter"])):
                spawn_obstacle(False); game_state["spawn_timer"] = 0
            for obs in game_state["obs_list"]: obs["x"] -= game_state["obs_speed"]
            if not process_collisions():
                game_state["player_dead_angle"] = 90;
                if death_sounds: random.choice(death_sounds).play()
                state = "gameover"
                record_run()
        elif mode == 1:
            is_moving = update_horizontal_movement("move_accel", "friction", 10.0)
            update_crouch_state()
            game_state["player_x"] = max(0, min(DESIGN_W - player_frames_right[0].get_width(), game_state["player_x"] + game_state["player_vx"]))
            apply_gravity_and_floor()
            if is_moving or not game_state["on_ground"]:
                game_state["anim_timer"] += 1
                if game_state["anim_timer"] >= game_state["anim_speed"]: game_state["anim_timer"] = 0; game_state["anim_index"] = (game_state["anim_index"] + 1) % len(player_frames_right)
            else: game_state["anim_timer"], game_state["anim_index"] = 0, 0
            game_state["spawn_timer"] += 1
            if game_state["spawn_timer"] >= game_state["spawn_interval"] + random.randint(-int(game_state["jitter"]), int(game_state["jitter"])):
                spawn_obstacle(False); game_state["spawn_timer"] = 0
            if random.random() < 0.02: game_state["coin_list"].append({"x": DESIGN_W, "y": random.randint(220, GROUND_Y - 90)})
            for obs in game_state["obs_list"]: obs["x"] -= game_state["obs_speed"]
            for c in game_state["coin_list"]: c["x"] -= game_state["obs_speed"]
            game_state["bg_x"] = (game_state["bg_x"] - game_state["obs_speed"] * game_state["bg_scroll_speed_scale"]) % -DESIGN_W
            process_coin_collisions()
            if not process_collisions():
                game_state["player_dead_angle"] = 90
                if death_sounds: random.choice(death_sounds).play()
                state = "gameover"
                record_run()
        elif mode == 2:
            is_moving = update_horizontal_movement("move_accel", "friction", game_state["move_speed"])
            update_crouch_state()
            game_state["player_x"] = max(game_state["cam_x"], game_state["player_x"] + game_state["player_vx"])
            apply_gravity_and_floor()
            if is_moving or not game_state["on_ground"]:
                game_state["anim_timer"] += 1
                if game_state["anim_timer"] >= game_state["anim_speed"]: game_state["anim_timer"] = 0; game_state["anim_index"] = (game_state["anim_index"] + 1) % len(player_frames_right)
            else: game_state["anim_timer"], game_state["anim_index"] = 0, 0
            game_state["spawn_timer"] += 1
            if game_state["spawn_timer"] >= game_state["spawn_interval"] + random.randint(-int(game_state["jitter"]), int(game_state["jitter"])):
                spawn_obstacle(True); game_state["spawn_timer"] = 0

            if random.random() < 0.05:
                spawn_coin_x = max(game_state["cam_x"] + DESIGN_W + 200, max((c["x"] for c in game_state["coin_list"]), default=0)) + random.randint(100, 400)
                game_state["coin_list"].append({"x": spawn_coin_x, "y": random.randint(220, GROUND_Y - 90)})

            game_state["cam_x"] = max(game_state["cam_x"], game_state["player_x"] - DESIGN_W // 3)
            game_state["bg_x"] = -(game_state["cam_x"] % DESIGN_W)
            process_coin_collisions()
            if not process_collisions():
                game_state["player_dead_angle"] = 90
                if death_sounds: random.choice(death_sounds).play()
                state = "gameover"
                record_run()
    for box in input_boxes: box.update()
    if state == "lock" and lock_err > 0: lock_err -= 1

    # GPU 模式下检测窗口尺寸变化（Window 不发 VIDEORESIZE）
    if GPU_OK and tuple(gpu_win.size) != _win_px:
        w_, h_ = gpu_win.size
        if not getattr(gpu_win, "fullscreen", False):
            w_, h_ = snap_aspect(w_, h_)        # 最大化/全屏时原样放行
            if (w_, h_) != tuple(gpu_win.size):
                gpu_win.size = (w_, h_)        # 直接把窗口掰回比例，画面不会被拖成扁的
        do_resize(w_, h_)
        ui_dirty = True

    # ---------- 渲染：按比例直接画到窗口，不做整屏缩放 ----------
    frame_dirty = ui_dirty       # 本帧是否有事件/尺寸/档位变化，供整页快照判断
    world_state = state in ("game", "paused", "gameover")
    if GPU_OK:
        # 背景与世界先画进场景纹理，画完再整块贴屏；模糊统一从这张纹理取样
        scene_begin()
        gpu_renderer.draw_color = (0, 0, 0, 255)
        gpu_renderer.clear()
        screen = gpu_target
    # 统一世界偏移：镜头换算成整数像素后，所有运动物体共用同一个位移，杜绝彼此相对抖动
    W_px, H_px = px(DESIGN_W), px(DESIGN_H)
    cam_px = int(round(game_state["cam_x"] * scale)) if game_state["game_mode"] == 2 else 0
    bg_d = bg_scaled(bg_img, W_px, H_px)
    if state in ("setup", "control", "lock"):
        bg_px = 0                      # 菜单/设置页/锁屏背景固定从相位 0 开始，不受上局滚动位置影响
    elif game_state["game_mode"] == 2:
        bg_px = -(cam_px % W_px) if W_px else 0
    else:
        bg_px = px(game_state["bg_x"])
    ww = WIN_SIZE()[0]
    if bg_d is None:
        screen.fill((0, 0, 0))
    elif (not GPU_OK) and (off_x > 0 or off_y > 0):
        # 软件模式没有整帧清屏：出现黑边时先把窗口刷黑，否则边条里会留着上一帧的残影
        screen.fill((0, 0, 0))
    if bg_d is not None:
        # 背景预拼成 2 倍宽横条，每帧只做一次整屏 blit（原先满屏 + 补条 = 两次）
        if _bg_tile["key"] != (id(bg_d), W_px, H_px):
            tile = pygame.Surface((W_px * 2, H_px))
            tile.blit(bg_d, (0, 0)); tile.blit(bg_d, (W_px, 0))
            _bg_tile["surf"], _bg_tile["key"] = tile, (id(bg_d), W_px, H_px)
        tile = _bg_tile["surf"]
        dx = off_x + bg_px
        sx0 = -dx if dx < 0 else 0
        # 背景必须严格裁在设计画布内：左右都停在画布边界上。
        # 以前左边按 0 起、右边按窗口宽度收，超宽屏上整幅画面会顶掉左边黑边、看着往右跑
        canvas_l, canvas_r = off_x, off_x + draw_w
        startx = dx + sx0
        if startx < canvas_l:
            sx0 += canvas_l - startx
            startx = canvas_l
        area_w = min(canvas_r - startx, W_px * 2 - sx0)
        if area_w > 0 and H_px > 0:
            screen.blit(tile, (dx + sx0, off_y), pygame.Rect(sx0, 0, area_w, min(H_px, WIN_SIZE()[1])))
        if GPU_OK and isinstance(screen, GpuTarget):
            # 记下背景纹理与相位，游戏内按钮的磨砂底从这块纹理里取样
            _bg_gpu["tex"] = screen.tex(tile)
            _bg_gpu["dx"] = dx
            _bg_gpu["oy"] = off_y
    # 从暂停页进「控制设置」时，背后的冻结尾帧要继续画，否则切页后人物/金币/障碍整片消失
    show_world = state in ("game", "paused", "gameover") or (state == "control_settings" and prev_state == "paused")
    if show_world:
        oh2 = off_y
        coin_s = ds(coin_img)                    # 金币图每帧会被多次取用，缩放版只查一次缓存
        for c in game_state["coin_list"]:
            cx_ = world_x(c["x"]); cy_ = oh2 + px(c["y"])
            if -160 <= cx_ <= ww: screen.blit(coin_s, (cx_, cy_))
        for obs in game_state["obs_list"]:
            oxx = world_x(obs["x"])
            if oxx <= ww and oxx + px(obs["w"]) >= 0: screen.blit(ds(obs["img"]), (oxx, oh2 + px(obs["y"])))
        draw_player(screen)
        for p_ in game_state["popups"]:
            psurf = text_surf(font, p_["text"], (255, 215, 60))
            screen.blit(psurf, (sx(p_["x"]) - psurf.get_width() // 2, sy(p_["y"] - 55)))
        screen.blit(text_surf(font, f"分数:{int(game_state['score'])}", (255,255,255)), (sx(30), sy(30)))
        screen.blit(text_surf(font, f"HP:{game_state['hp']}/{game_state['max_hp']}", (255,80,80)), (sx(30), sy(80)))
        if game_state["game_mode"] != 0: screen.blit(text_surf(font, f"金币:{game_state['coin_cnt']}", (255, 215, 60)), (sx(30), sy(130)))
    if GPU_OK:
        scene_end()      # 世界/背景画完，整块贴屏；后面的模糊都去采样这张纹理
    # 渲染 UI
    if state == "lock":
        blur_surf = get_tinted_blur(screen, "lock", (0, 0, 0, 0))
        screen.blit(blur_surf, (0, 0))
        ui_fill_alpha(screen, pygame.Rect(0, 0, DESIGN_W, DESIGN_H), (4, 6, 10, 140))
        draw_lock_screen(screen, blur_surf)
    if state == "setup":
        blur_surf = get_tinted_blur(screen, "setup", (0, 0, 0, 0))
        layout_setup_ui()      # 热区每帧都摆正，快照可以复用旧纹理但点击不能错位
        # 正在输入（光标闪烁）或数据页开着时不走快照，直接实时画
        live = (not GPU_OK) or stats_open or any(b.active for b in input_boxes)
        if GPU_OK and not live:
            # 脏判据：本帧有事件/尺寸变化，或窗口尺寸、模糊档位变了；干净帧直接复用纹理
            key = (WIN_SIZE(), blur_level)
            if _setup_snap_tex is None or _setup_snap_sig != key or frame_dirty:
                # 尺寸没变就复用同一张纹理，拖动滑条这种连续脏帧不会疯狂新建/销毁 FBO
                if (_setup_snap_tex is None
                        or (_setup_snap_tex.width, _setup_snap_tex.height) != WIN_SIZE()):
                    if _setup_snap_tex is not None: _setup_snap_tex.destroy()
                    _setup_snap_tex = new_tex(WIN_SIZE(), target=True)
                t = _setup_snap_tex
                pt, pb = gpu_renderer.target, gpu_renderer.draw_blend_mode
                gpu_renderer.target = t
                # 只有清屏和贴底图用不透明；控件必须切回 BLEND，否则玻璃/压暗/文字全糊成实心块
                gpu_renderer.draw_blend_mode = pygame.BLENDMODE_NONE
                gpu_renderer.draw_color = (0, 0, 0, 255)
                gpu_renderer.clear()
                screen.blit(blur_surf, (0, 0))
                gpu_renderer.draw_blend_mode = pygame.BLENDMODE_BLEND
                draw_setup_ui(screen, blur_surf)
                gpu_renderer.target, gpu_renderer.draw_blend_mode = pt, pb
                _setup_snap_sig = key
            prev_b = gpu_renderer.draw_blend_mode
            gpu_renderer.draw_blend_mode = pygame.BLENDMODE_NONE
            gpu_renderer.blit(_setup_snap_tex, pygame.Rect(0, 0, WIN_SIZE()[0], WIN_SIZE()[1]))
            gpu_renderer.draw_blend_mode = prev_b
        else:
            screen.blit(blur_surf, (0, 0))
            draw_setup_ui(screen, blur_surf)
        if stats_open:
            # 数据页背景 = 设置页整页的模糊版（设置页静止，模糊版只算一次并缓存）
            if _stats_bg is None or _stats_bg_size != WIN_SIZE():
                sw, sh = WIN_SIZE()
                if GPU_OK:
                    # 回读一次当前帧 → 和别的页面同一套 page_blur_px()，不再单独加码
                    page = gpu_renderer.to_surface()
                    tmp_tex = tex_from_surface(page)
                    bgtex = new_tex((sw, sh), target=True)
                    tex, tw, th = blur_region(tmp_tex, pygame.Rect(0, 0, sw, sh), sw, sh,
                                              radius=page_blur_px(), dst_tex=bgtex)
                    tmp_tex.destroy()
                    _stats_bg = GpuBlur(tex, sw, sh)
                else:
                    # 软件加速没有高斯核，档位直接决定降采样倍数，同样不另开参数
                    k_ = max(2, int(round(14 * blur_mul())))
                    tiny = pygame.transform.smoothscale(screen, (max(1, sw // k_), max(1, sh // k_)))
                    _stats_bg = pygame.transform.smoothscale(tiny, (sw, sh))
                _stats_bg_size = (sw, sh)
            screen.blit(_stats_bg, (0, 0))
            if _stats_dim is None or _stats_dim.get_size() != WIN_SIZE():
                _stats_dim = pygame.Surface(WIN_SIZE(), pygame.SRCALPHA)
                _stats_dim.fill((0, 0, 0, 168))
            screen.blit(_stats_dim, (0, 0))
            # 分组卡片：三行成绩统一坐在一块淡玻璃上，和设置页的卡片同一套材质
            ui_fill_alpha(screen, pygame.Rect(DESIGN_W//2 - 470, 214, 940, 544), (255, 255, 255, 30))
            t_title = text_surf(font_big, "游戏数据", (255, 255, 255))
            screen.blit(t_title, t_title.get_rect(center=(sx(DESIGN_W // 2), sy(150))))
            hdr_s = text_surf(font, "分数", (200, 200, 200))
            hdr_c = text_surf(font, "金币", (200, 200, 200))
            screen.blit(hdr_s, hdr_s.get_rect(center=(sx(DESIGN_W // 2 + 120), sy(255))))
            screen.blit(hdr_c, hdr_c.get_rect(center=(sx(DESIGN_W // 2 + 330), sy(255))))
            rows = [("跑步鸡", best_stats["s0"], None),
                    ("滑步鸡", best_stats["s1"], best_stats["c1"]),
                    ("吴国鸡", best_stats["s2"], best_stats["c2"])]
            for idx, (name, score, coins) in enumerate(rows):
                ly = 360 + idx * 150
                n_surf = text_surf(font_big, name, (255, 255, 255))
                screen.blit(n_surf, n_surf.get_rect(center=(sx(DESIGN_W // 2 - 330), sy(ly))))
                s_surf = text_surf(font_big, str(score), (255, 215, 60))
                screen.blit(s_surf, s_surf.get_rect(center=(sx(DESIGN_W // 2 + 120), sy(ly))))
                if coins is None:
                    c_surf = text_surf(font, "无", (150, 150, 150))
                else:
                    c_surf = text_surf(font_big, str(coins), (255, 215, 60))
                screen.blit(c_surf, c_surf.get_rect(center=(sx(DESIGN_W // 2 + 330), sy(ly))))
                if idx < len(rows) - 1:      # 最后一行不再画分隔线，免得贴着卡片下边
                    ui_line(screen, (255, 255, 255, 40), (sx(DESIGN_W // 2 - 430), sy(ly + 70)),
                                     (sx(DESIGN_W // 2 + 430), sy(ly + 70)), max(1, px(1)))
            btn_stats_close.draw(screen, blur_surf)
    elif state == "game":
        b_surf = blur_engine   # 游戏内只需按钮区域的模糊，交给 draw_ios_glass 按区域计算
        btn_pause.draw(screen, b_surf)

        if game_state["input_mode"] == 1:
            # 三种模式统一：跳、蹲两个按钮；左右只给有横向移动的模式一/二
            if game_state["game_mode"] != 0:
                btn_left.draw(screen, b_surf)
                btn_right.draw(screen, b_surf)
            btn_jump.draw(screen, b_surf)
            btn_crouch.draw(screen, b_surf)
    elif state == "paused":
        blur_surf = get_tinted_blur(screen, "paused", (0, 0, 0, 60))
        screen.blit(blur_surf, (0, 0))
        ui_fill_alpha(screen, pygame.Rect(0, 0, DESIGN_W, DESIGN_H), (6, 8, 12, 150))
        p_text = text_surf(font_big, "游戏暂停", (255, 255, 255))
        screen.blit(p_text, p_text.get_rect(center=(sx(DESIGN_W//2), sy(DESIGN_H//2 - 200))))

        btn_resume.draw(screen, blur_surf)
        btn_pause_control.draw(screen, blur_surf)
        btn_quit.draw(screen, blur_surf)
    elif state == "gameover":
        blur_surf = get_tinted_blur(screen, "gameover", (26, 0, 0, 40))
        screen.blit(blur_surf, (0, 0))
        # 模糊底本身只能用加色，压暗得靠单独的半透明黑层
        ui_fill_alpha(screen, pygame.Rect(0, 0, DESIGN_W, DESIGN_H), (8, 3, 6, 155))
        over_text = text_surf(font_big, "你死了", (255,255,255))
        screen.blit(over_text, over_text.get_rect(center=(sx(DESIGN_W//2), sy(DESIGN_H//2 - 50))))

        result_text = f"分数:{int(game_state['score'])}" if game_state["game_mode"] == 0 else f"分数:{int(game_state['score'])}  金币:{game_state['coin_cnt']}"
        result_line = text_surf(font, result_text, (255, 255, 255))
        screen.blit(result_line, result_line.get_rect(center=(sx(DESIGN_W//2), sy(DESIGN_H//2))))
        btn_retry.draw(screen, blur_surf)
    elif state == "control_settings":
        blur_surf = get_tinted_blur(screen, "settings", (0, 0, 0, 160))
        screen.blit(blur_surf, (0, 0))

        # 整屏压暗，左栏再压深一点：iPadOS 式两栏
        ui_fill_alpha(screen, pygame.Rect(0, 0, DESIGN_W, DESIGN_H), (8, 10, 14, 210))
        ui_fill_alpha(screen, pygame.Rect(0, 0, 560, DESIGN_H), (0, 0, 0, 45))
        ui_line(screen, (255, 255, 255, 45), (sx(560), 0), (sx(560), sy(DESIGN_H)), max(1, px(1)))

        tab = SETTINGS_TABS[settings_tab]
        in_game = prev_state == "paused"

        # 左栏：标题 + 项目行（纯文字，直角淡底选中，不上色）
        s_title = text_surf(font_big, "设置", (255, 255, 255))
        screen.blit(s_title, s_title.get_rect(left=sx(60), centery=sy(96)))

        for i, b in enumerate(nav_btns):
            if i == settings_tab:
                ui_fill_alpha(screen, b.rect, (255, 255, 255, 55))
            it = text_surf(font, SETTINGS_TABS[i],
                           (255, 255, 255) if i == settings_tab else (222, 222, 222))
            screen.blit(it, it.get_rect(left=sx(b.rect.x + 30), centery=sy(b.rect.centery)))

        # 右栏：页面标题 + 直角分组块
        p_title = text_surf(font_big, tab, (255, 255, 255))
        screen.blit(p_title, p_title.get_rect(left=sx(620), centery=sy(96)))

        if tab == "控制设置":
            ui_fill_alpha(screen, pygame.Rect(620, 220, 1260, 300), (255, 255, 255, 26))
            lab = text_surf(font, "操作方式", (255, 255, 255))
            screen.blit(lab, lab.get_rect(left=sx(660), centery=sy(300)))
            btn_kbd.rect = kbd_opt_rect
            btn_tch.rect = tch_opt_rect
            style_option(btn_kbd, game_state["input_mode"] == 0)
            style_option(btn_tch, game_state["input_mode"] == 1)
            btn_kbd.draw(screen, blur_surf)
            btn_tch.draw(screen, blur_surf)
            tip = text_surf(font_small, "键盘：空格跳跃，A/D 移动，Shift下蹲    触屏：方向键 + ▲跳跃/ ▼下蹲",
                            (215, 215, 215))
            screen.blit(tip, tip.get_rect(left=sx(660), centery=sy(588)))

        elif tab == "模糊设置":
            ui_fill_alpha(screen, pygame.Rect(620, 220, 1260, 300), (255, 255, 255, 26))
            lab = text_surf(font, "模糊强度", (255, 255, 255))
            screen.blit(lab, lab.get_rect(left=sx(660), centery=sy(300)))
            blur_ok = current_backend_key() != "soft"
            for i, b in enumerate(blur_opt_btns):
                if blur_ok:
                    style_option(b, i == blur_level)
                else:
                    b.is_dark = True
                    b.selected = False
                    b.text_color = (165, 165, 165)
                    b.font_surf = b.font.render(b.text, True, b.text_color)
                    b._style_key = None
                b.draw(screen, blur_surf)
            if blur_ok:
                tip = text_surf(font_small, "弱 0.45×    中 1.0×    强 1.65×    控制全局模糊",
                                (215, 215, 215))
                screen.blit(tip, tip.get_rect(left=sx(660), centery=sy(588)))
            else:
                warn = text_surf(font_small, "软件加速渲染时，模糊调整不可用", (255, 205, 70))
                screen.blit(warn, warn.get_rect(left=sx(660), centery=sy(560)))

        elif tab == "参数设置":
            if not in_game:
                ui_fill_alpha(screen, pygame.Rect(620, 220, 1260, 300), (255, 255, 255, 26))
                msg = text_surf(font_big, "仅在游戏内可用", (205, 205, 205))
                screen.blit(msg, msg.get_rect(center=(sx(1250), sy(330))))
                tip = text_surf(font_small, "进入游戏后从暂停页打开设置，就能边玩边调，改完立即生效",
                                (205, 205, 205))
                screen.blit(tip, tip.get_rect(center=(sx(1250), sy(400))))
            else:
                btn_reset_ingame_all.draw(screen, blur_surf)
                ui_fill_alpha(screen, pygame.Rect(620, 190, 1260, 700), (255, 255, 255, 26))
                for i, box in enumerate(input_boxes):
                    cy = 216 + i * 47 + 17
                    label_surf = text_surf(font_small, box_labels[i], (255, 255, 255))
                    screen.blit(label_surf, label_surf.get_rect(left=sx(650), centery=sy(cy)))
                    sliders[i].rect.x = 1020
                    sliders[i].rect.width = 300
                    sliders[i].rect.centery = cy
                    box.rect.width, box.rect.height = 140, 34
                    box.rect.x, box.rect.centery = 1372, cy
                    box.btn_minus.rect.update(1330, box.rect.y, 36, 34)
                    box.btn_plus.rect.update(1518, box.rect.y, 36, 34)
                    sliders[i].draw(screen, blur_surf)
                    box.draw(screen, blur_surf)
                    rb = reset_glass_btns[i]
                    rb.rect.update(1740, cy - 16, 90, 32)
                    rb.draw(screen, blur_surf)
                tip = text_surf(font_small, "改完立即生效，同步主菜单设置页", (205, 205, 205))
                screen.blit(tip, tip.get_rect(left=sx(650), centery=sy(915)))

        elif tab == "渲染器":
            ui_fill_alpha(screen, pygame.Rect(620, 214, 1260, 330), (255, 255, 255, 26))
            lab = text_surf(font, "渲染器选择", (255, 255, 255))
            screen.blit(lab, lab.get_rect(left=sx(660), centery=sy(258)))
            cur = current_backend_key()
            avail = {"gl": OPENGL_OK, "vk": False, "soft": True}
            for i, b in enumerate(render_opt_btns):
                name, key = RENDER_CHOICES[i]
                if not avail.get(key, False):
                    # 用不了的后端：灰字、不选中，点了只给一句「与当前系统不兼容」
                    b.is_dark = True
                    b.selected = False
                    b.text_color = (165, 165, 165)
                    b.font_surf = b.font.render(b.text, True, b.text_color)
                    b._style_key = None
                else:
                    style_option(b, key == cur or (key == "gl" and cur == "gpu"))
                b.draw(screen, blur_surf)
            for i, desc in enumerate(RENDER_DESCS):
                dtxt = text_surf(font_small, desc, (200, 200, 200))
                screen.blit(dtxt, dtxt.get_rect(left=sx(660), centery=sy(436 + i * 28)))
            tip = text_surf(font_small, "切换后重启游戏生效；命令行设置 WUGUO_RENDER 会覆盖此处的选择",
                            (215, 215, 215))
            screen.blit(tip, tip.get_rect(left=sx(660), centery=sy(588)))

            # 硬件 / 引擎信息：把渲染管线摊开，别让这一页只有三颗按钮
            ui_fill_alpha(screen, pygame.Rect(620, 616, 1260, 232), (255, 255, 255, 26))
            gl_info = {}
            if cur == "gl":
                try: gl_info = gpu_renderer.info()
                except Exception: gl_info = {}
            pending_name = dict((k, n) for n, k in RENDER_CHOICES).get(render_pending, "无")
            infos = [
                ("渲染器", current_backend_name()),
                ("待生效", pending_name),
                ("显卡", gl_info.get("renderer", "—（软件加速）")),
                ("GL 版本", (gl_info.get("gl_version") or "—").split(" ")[0]),
                ("模糊引擎", "GLSL 双可分离高斯" if cur == "gl" else "双 SmoothScale 缩放"),
                ("设计分辨率", f"{DESIGN_W}×{DESIGN_H}"),
                ("窗口尺寸", "%d×%d" % WIN_SIZE()),
                ("模糊", f"{BLUR_LEVELS[blur_level][0]} {BLUR_LEVELS[blur_level][1]}×"),
            ]
            for idx, (k, v) in enumerate(infos):
                col, row = idx % 2, idx // 2
                cx_ = 660 + col * 640
                cy_ = 660 + row * 46
                k_surf = text_surf(font_small, k, (190, 190, 190))
                screen.blit(k_surf, k_surf.get_rect(left=sx(cx_), centery=sy(cy_)))
                # 有待生效的东西时，「待生效」的值标黄，一眼能看出还没重启
                v_color = (255, 205, 70) if (k == "待生效" and render_pending) else (255, 255, 255)
                v_surf = text_surf(font_small, str(v), v_color)
                screen.blit(v_surf, v_surf.get_rect(left=sx(cx_ + 170), centery=sy(cy_)))

            if render_pending:
                btn_cancel.draw(screen, blur_surf)
                btn_relaunch.draw(screen, blur_surf)
            if toast_alive():
                m_surf = text_surf(font_small, toast_text, (255, 205, 70))
                screen.blit(m_surf, m_surf.get_rect(left=sx(660), centery=sy(862)))

        elif tab == "作弊选项":
            ui_fill_alpha(screen, pygame.Rect(620, 220, 1260, 336), (255, 255, 255, 26))
            for label, desc, on, btn in (
                    ("无碰撞体积", "穿过障碍物，不判定撞击", game_state["no_collision"], btn_nc),
                    ("无敌不掉血", "撞到障碍物不扣血", game_state["no_damage"], btn_nd)):
                txt = text_surf(font, label, (255, 255, 255))
                screen.blit(txt, txt.get_rect(left=sx(680), centery=sy(btn.rect.centery - 18)))
                d = text_surf(font_small, desc, (210, 210, 210))
                screen.blit(d, d.get_rect(left=sx(680), centery=sy(btn.rect.centery + 28)))
                btn.is_dark = True
                want = "开" if on else "关"
                key = (want, on)
                if btn._style_key != key:
                    btn.text = want
                    btn.text_color = (120, 245, 150) if on else (225, 225, 225)
                    btn.font_surf = btn.font.render(want, True, btn.text_color)
                    btn._style_key = key
                btn.draw(screen, blur_surf)
            # 局内只要开过就一直挂着；主菜单页则看当前开关，关了立刻消失
            ui_line(screen, (255, 255, 255, 35), (sx(660), sy(380)), (sx(1840), sy(380)), max(1, px(1)))
            warn = run_cheated if in_game else (game_state["no_collision"] or game_state["no_damage"])
            tip = text_surf(font_small, "选项立即生效", (205, 205, 205))
            screen.blit(tip, tip.get_rect(left=sx(660), centery=sy(600)))
            if warn:
                w_text = "本局分数将不会保存" if in_game else "新的一局分数将不会保存"
                w_surf = text_surf(font, w_text, (255, 205, 70))
                screen.blit(w_surf, w_surf.get_rect(left=sx(660), centery=sy(658)))

        btn_back.rect.update(60, 880, 460, 80)
        btn_back.draw(screen, blur_surf)

    # 黑边保持纯黑：整屏模糊层会从画布往外渗一圈，最后一步把边条重新涂死
    if off_x > 1 or off_y > 1:
        _ww, _wh = WIN_SIZE()
        lw, th = int(off_x), int(off_y)
        rw, bh = _ww - lw - draw_w, _wh - th - draw_h
        if lw > 0: ui_rect(screen, (0, 0, 0), pygame.Rect(0, 0, lw, _wh))
        if rw > 0: ui_rect(screen, (0, 0, 0), pygame.Rect(lw + draw_w, 0, rw, _wh))
        if th > 0: ui_rect(screen, (0, 0, 0), pygame.Rect(0, 0, _ww, th))
        if bh > 0: ui_rect(screen, (0, 0, 0), pygame.Rect(0, th + draw_h, _ww, bh))

    # ---------- 呈现 ----------
    if GPU_OK:
        gpu_renderer.present()     # 整帧都是纹理绘制，没有一次上传来挡路
    else:
        pygame.display.flip()
    ui_dirty = False               # 脏标记本帧已经被所有消费者用过，下一帧从干净开始

if GPU_OK:
    if _setup_snap_tex is not None:
        try: _setup_snap_tex.destroy()
        except Exception: pass
    try: gpu_win.destroy()
    except Exception: pass
pygame.quit()
