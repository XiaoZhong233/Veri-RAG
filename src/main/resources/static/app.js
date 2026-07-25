const API_BASE = document.querySelector('meta[name="api-base"]').content.replace(/\/$/, '');
const state = {
    token: localStorage.getItem('veri-rag-token'), user: null, view: 'chat',
    userPage: 1, userSize: 10, userTotal: 0,
    documentPage: 1, documentSize: 10, documentTotal: 0,
    categories: [], activeSessionId: null, selectedDocumentIds: new Set()
};
const $ = (selector) => document.querySelector(selector);

async function request(path, options = {}) {
    const isFormData = options.body instanceof FormData;
    const headers = { ...(options.body && !isFormData ? {'Content-Type': 'application/json'} : {}), ...(options.headers || {}) };
    if (state.token) headers.Authorization = `Bearer ${state.token}`;
    const response = await fetch(`${API_BASE}${path}`, {...options, headers});
    const payload = await response.json().catch(() => ({code: response.status, message: '服务器返回了无效响应'}));
    if (!response.ok || payload.code !== 200) {
        if (response.status === 401 || payload.code === 401) logout(false);
        throw new Error(payload.message || '请求失败');
    }
    return payload.data;
}

async function streamRequest(path, body, onEvent) {
    const response = await fetch(`${API_BASE}${path}`, {
        method: 'POST', headers: {'Content-Type': 'application/json', Authorization: `Bearer ${state.token}`}, body: JSON.stringify(body)
    });
    if (!response.ok || !response.body) {
        const payload = await response.json().catch(() => ({message: '流式请求失败'}));
        if (response.status === 401 || payload.code === 401) logout(false);
        throw new Error(payload.message || '流式请求失败');
    }
    const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = '';
    while (true) {
        const {done, value} = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), {stream: !done}).replace(/\r\n/g, '\n');
        let boundary;
        while ((boundary = buffer.indexOf('\n\n')) >= 0) {
            const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
            let event = 'message'; const data = [];
            frame.split('\n').forEach(line => { if (line.startsWith('event:')) event = line.slice(6).trim(); if (line.startsWith('data:')) data.push(line.slice(5).trimStart()); });
            if (data.length) onEvent(event, JSON.parse(data.join('\n')));
        }
        if (done) break;
    }
}

function escapeHtml(value = '') { const element = document.createElement('div'); element.textContent = String(value); return element.innerHTML; }
function formatDate(value) { return value ? new Intl.DateTimeFormat('zh-CN', {dateStyle: 'medium', timeStyle: 'short'}).format(new Date(value)) : '-'; }
function initials(user) { return (user.realName || user.username || '?').trim().slice(0, 1).toUpperCase(); }
function avatarUrl(path) { return `${API_BASE}/files/${path.split('/').map(encodeURIComponent).join('/')}`; }
function avatarMarkup(user, extraClass = '') { return user.avatar ? `<span class="avatar ${extraClass}"><img src="${avatarUrl(user.avatar)}" alt=""></span>` : `<span class="avatar ${extraClass}">${escapeHtml(initials(user))}</span>`; }
function showToast(message) { const toast = $('#toast'); toast.textContent = message; toast.classList.add('show'); clearTimeout(showToast.timer); showToast.timer = setTimeout(() => toast.classList.remove('show'), 2600); }
function isAdmin() { return state.user?.role === 'ADMIN'; }

function setLoginError(message = '') { $('#login-error').textContent = message; }
async function login(event) {
    event.preventDefault(); setLoginError();
    const button = $('#login-submit'); button.disabled = true; button.textContent = '正在登录…';
    try {
        const data = await request('/api/auth/login', {method: 'POST', body: JSON.stringify({username: $('#login-username').value.trim(), password: $('#login-password').value})});
        state.token = data.token; state.user = data.user;
        localStorage.setItem('veri-rag-token', data.token); localStorage.setItem('veri-rag-user', JSON.stringify(data.user));
        await showConsole();
    } catch (error) { setLoginError(error.message); }
    finally { button.disabled = false; button.textContent = '登录'; }
}
function logout(showMessage = true) {
    state.token = null; state.user = null; state.activeSessionId = null;
    localStorage.removeItem('veri-rag-token'); localStorage.removeItem('veri-rag-user');
    $('#console-view').classList.add('hidden'); $('#login-view').classList.remove('hidden'); $('#login-form').reset();
    if (showMessage) showToast('已退出登录');
}
async function showConsole() {
    state.user = state.user || JSON.parse(localStorage.getItem('veri-rag-user') || 'null');
    if (!state.user) return logout(false);
    $('#login-view').classList.add('hidden'); $('#console-view').classList.remove('hidden');
    document.querySelectorAll('.admin-only').forEach(item => item.classList.toggle('hidden', !isAdmin()));
    renderCurrentUser(); await Promise.all([refreshCurrentUser(), loadCategories(), loadSessions()]); showView('chat');
}
async function refreshCurrentUser() {
    try { state.user = await request('/api/users/me'); localStorage.setItem('veri-rag-user', JSON.stringify(state.user)); renderCurrentUser(); }
    catch (error) { if (state.token) showToast(error.message); }
}
function renderCurrentUser() {
    const avatar = $('#current-avatar'); avatar.replaceChildren(); avatar.textContent = initials(state.user);
    if (state.user.avatar) { const image = new Image(); image.src = avatarUrl(state.user.avatar); image.alt = ''; image.onerror = () => { avatar.replaceChildren(); avatar.textContent = initials(state.user); }; avatar.replaceChildren(image); }
    $('#current-user-name').textContent = `${state.user.realName || state.user.username} · ${state.user.role}`;
    $('#profile-real-name').value = state.user.realName || '';
}

const viewInfo = {chat: ['VERIRAG', '智能问答'], knowledge: ['KNOWLEDGE BASE', '知识库'], users: ['ADMINISTRATION', '用户管理'], profile: ['ACCOUNT', '个人设置']};
function showView(view) {
    if (view === 'users' && !isAdmin()) { showToast('没有用户管理权限'); return; }
    state.view = view;
    Object.keys(viewInfo).forEach(name => $(`#${name}-view`).classList.toggle('hidden', name !== view));
    document.querySelectorAll('.nav-item').forEach(item => item.classList.toggle('active', item.dataset.view === view));
    $('#view-eyebrow').textContent = viewInfo[view][0]; $('#view-title').textContent = viewInfo[view][1];
    if (view === 'users') loadUsers();
    if (view === 'knowledge') { renderCategories(); loadDocuments(); }
}

async function loadCategories() {
    try { state.categories = await request('/api/categories'); renderCategoryInputs(); renderCategories(); }
    catch (error) { showToast(error.message); }
}
function categoryIcon(icon) {
    const value = String(icon || '').trim();
    const legacyIcons = {
        Document: '📄',
        Notebook: '📋',
        Goods: '📦',
        QuestionFilled: '❓'
    };
    return legacyIcons[value] || value || '▤';
}
function renderCategoryInputs() {
    const selectedDocumentCategory = $('#document-category').value;
    const selectedUploadCategory = $('#upload-category').value;
    const optionHtml = state.categories.map(c => `<option value="${c.id}">${escapeHtml(categoryIcon(c.icon))} ${escapeHtml(c.name)}</option>`).join('');
    $('#document-category').innerHTML = `<option value="">全部分类</option>${optionHtml}`;
    $('#upload-category').innerHTML = `<option value="">请选择分类</option>${optionHtml}`;
    $('#document-category').value = state.categories.some(c => String(c.id) === selectedDocumentCategory) ? selectedDocumentCategory : '';
    $('#upload-category').value = state.categories.some(c => String(c.id) === selectedUploadCategory) ? selectedUploadCategory : '';
    $('#chat-category-filter').innerHTML = `<button class="filter-chip active" data-category="" type="button">全部知识库</button>${state.categories.map(c => `<button class="filter-chip" data-category="${c.id}" type="button">${escapeHtml(categoryIcon(c.icon))} ${escapeHtml(c.name)}</button>`).join('')}`;
}
function renderCategories() {
    const list = $('#category-list');
    const selectedId = $('#document-category')?.value || '';
    list.innerHTML = state.categories.length
        ? `<div class="category-list-meta"><span>共 ${state.categories.length} 个分类</span><span>点击分类筛选文档</span></div>${state.categories.map(c => `<article class="category-card ${String(c.id) === selectedId ? 'selected' : ''}"><button class="category-select" data-category-id="${c.id}" type="button" title="查看“${escapeHtml(c.name)}”中的文档"><span class="category-icon" aria-hidden="true">${escapeHtml(categoryIcon(c.icon))}</span><span class="category-copy"><strong>${escapeHtml(c.name)}</strong><span>${escapeHtml(c.description || '暂未添加分类说明')}</span></span><span class="category-arrow" aria-hidden="true">→</span></button>${isAdmin() ? `<button class="category-delete delete-category" data-id="${c.id}" type="button" aria-label="删除分类 ${escapeHtml(c.name)}" title="删除分类">×</button>` : ''}</article>`).join('')}`
        : '<div class="category-empty"><span>▤</span><strong>还没有知识分类</strong><p>新建一个分类后，上传文档时即可归档和筛选。</p></div>';
}
async function saveCategory(event) {
    event.preventDefault(); $('#category-error').textContent = '';
    const payload = {name: $('#category-name').value.trim(), description: $('#category-description').value.trim(), icon: $('#category-icon').value.trim(), sortOrder: Number($('#category-sort-order').value || 0)};
    try { await request('/api/categories', {method: 'POST', body: JSON.stringify(payload)}); $('#category-dialog').close(); event.target.reset(); showToast('分类已保存'); loadCategories(); }
    catch (error) { $('#category-error').textContent = error.message; }
}

async function loadDocuments() {
    if (state.view !== 'knowledge') return;
    try {
        const query = new URLSearchParams({keyword: $('#document-keyword').value.trim(), page: state.documentPage, size: state.documentSize});
        if ($('#document-category').value) query.set('categoryId', $('#document-category').value);
        const result = await request(`/api/documents/page?${query}`); state.documentTotal = result.total; renderDocuments(result.records);
        const pages = Math.max(Math.ceil(result.total / state.documentSize), 1); $('#document-pagination-info').textContent = `共 ${result.total} 个文档`; $('#document-page-number').textContent = `${state.documentPage} / ${pages}`;
        $('#document-prev').disabled = state.documentPage <= 1; $('#document-next').disabled = state.documentPage >= pages;
    } catch (error) { showToast(error.message); }
}
function renderDocuments(records) {
    const categories = new Map(state.categories.map(c => [c.id, c.name]));
    $('#document-list').innerHTML = records.length ? records.map(d => `<article class="document-row">${isAdmin() ? `<label class="document-selector"><input class="document-select" data-id="${d.id}" type="checkbox" ${state.selectedDocumentIds.has(d.id) ? 'checked' : ''}><span class="sr-only">选择 ${escapeHtml(d.title)}</span></label>` : ''}<span class="file-badge">${escapeHtml((d.fileType || '?').toUpperCase())}</span><div class="document-main"><strong>${escapeHtml(d.title)}</strong><p>${escapeHtml(categories.get(d.categoryId) || '未分类')} · ${escapeHtml(d.fileName || '')} · ${d.vectorCount ?? 0} 个片段</p></div><span class="status-tag ${String(d.status).toLowerCase()}">${escapeHtml(d.status)}</span><span class="muted">${formatDate(d.createTime)}</span>${isAdmin() ? `<span class="document-actions"><button class="text-button reingest-document" data-id="${d.id}" data-title="${escapeHtml(d.title)}" type="button">重新向量化</button><button class="text-button danger delete-document" data-id="${d.id}" data-title="${escapeHtml(d.title)}" type="button">删除</button></span>` : ''}</article>`).join('') : '<p class="empty-state">没有找到文档。</p>';
    updateBatchReingestButton();
}

function updateBatchReingestButton() {
    const button = $('#batch-reingest-button');
    if (!button) return;
    const count = state.selectedDocumentIds.size;
    button.disabled = count === 0;
    button.textContent = `批量重新向量化（${count}）`;
}

async function batchReingestDocuments() {
    const ids = [...state.selectedDocumentIds];
    if (!ids.length || !confirm(`确定重新向量化已选择的 ${ids.length} 个文档吗？过程可能需要一些时间。`)) return;
    const button = $('#batch-reingest-button');
    button.disabled = true; button.textContent = '批量处理中…';
    try {
        const result = await request('/api/documents/reingest', {method: 'POST', body: JSON.stringify({documentIds: ids})});
        state.selectedDocumentIds.clear();
        showToast(result.failedCount ? `完成：成功 ${result.successCount}，失败 ${result.failedCount}` : `已完成 ${result.successCount} 个文档的重新向量化`);
        if (result.failedCount) console.warn('Batch re-vectorization failures:', result.items.filter(item => !item.success));
        await loadDocuments();
    } catch (error) {
        showToast(error.message);
        updateBatchReingestButton();
    }
}
async function uploadDocument(event) {
    event.preventDefault(); $('#document-error').textContent = '';
    const form = new FormData(); form.append('file', $('#document-file').files[0]); form.append('categoryId', $('#upload-category').value); if ($('#document-title').value.trim()) form.append('title', $('#document-title').value.trim());
    const button = $('#document-submit'); button.disabled = true; button.textContent = '正在上传…';
    try { await request('/api/documents', {method: 'POST', body: form}); $('#document-dialog').close(); event.target.reset(); showToast('文档已上传并完成向量化'); loadDocuments(); }
    catch (error) { $('#document-error').textContent = error.message; }
    finally { button.disabled = false; button.textContent = '上传并向量化'; }
}

async function loadSessions() {
    try {
        const sessions = await request('/api/chat/sessions'); const list = $('#session-list');
        list.innerHTML = sessions.length ? sessions.map(s => `<button class="session-item ${s.id === state.activeSessionId ? 'active' : ''}" data-id="${s.id}" type="button"><span>${escapeHtml(s.title || '未命名会话')}</span><small>${formatDate(s.createTime)}</small><i data-delete-session="${s.id}" title="删除会话">×</i></button>`).join('') : '<p class="session-empty">暂无历史会话</p>';
    } catch (error) { showToast(error.message); }
}
async function openSession(id) {
    state.activeSessionId = Number(id); $('#chat-empty').classList.add('hidden'); await loadSessions();
    try { const messages = await request(`/api/chat/sessions/${id}/messages`); renderMessages(messages); }
    catch (error) { showToast(error.message); }
}
function newChat() { state.activeSessionId = null; $('#message-list').replaceChildren(); $('#chat-empty').classList.remove('hidden'); loadSessions(); $('#chat-question').focus(); }
function renderMessages(messages) {
    const list = $('#message-list'); list.replaceChildren(); messages.forEach(message => appendMessage(message.role, message.content, parseRefs(message.refs))); list.scrollTop = list.scrollHeight;
}
function parseRefs(value) { try { return value ? JSON.parse(value) : []; } catch { return []; } }

/**
 * 将模型输出安全地渲染为一个有限的 Markdown 子集。
 * 不把模型文本直接赋给 innerHTML，避免不可信模型输出带来 XSS；解析异常则回退成纯文本。
 */
function renderMarkdown(container, markdown) {
    try {
        if (typeof markdown !== 'string') throw new TypeError('Markdown content must be a string');
        const lines = markdown.replace(/\r\n?/g, '\n').split('\n');
        const fragment = document.createDocumentFragment();
        let index = 0;
        while (index < lines.length) {
            const line = lines[index];
            if (!line.trim()) { index++; continue; }
            if (line.startsWith('```')) {
                const language = line.slice(3).trim(); const code = []; index++;
                while (index < lines.length && !lines[index].startsWith('```')) code.push(lines[index++]);
                if (index < lines.length) index++;
                const pre = document.createElement('pre'); const codeNode = document.createElement('code');
                if (language) codeNode.dataset.language = language;
                codeNode.textContent = code.join('\n'); pre.appendChild(codeNode); fragment.appendChild(pre); continue;
            }
            const heading = line.match(/^(#{1,6})\s+(.+)$/);
            if (heading) { const node = document.createElement(`h${heading[1].length}`); appendInlineMarkdown(node, heading[2]); fragment.appendChild(node); index++; continue; }
            if (/^\s*[-*+]\s+/.test(line)) {
                const list = document.createElement('ul');
                while (index < lines.length && /^\s*[-*+]\s+/.test(lines[index])) { const item = document.createElement('li'); appendInlineMarkdown(item, lines[index].replace(/^\s*[-*+]\s+/, '')); list.appendChild(item); index++; }
                fragment.appendChild(list); continue;
            }
            if (/^\s*\d+[.)]\s+/.test(line)) {
                const list = document.createElement('ol');
                while (index < lines.length && /^\s*\d+[.)]\s+/.test(lines[index])) { const item = document.createElement('li'); appendInlineMarkdown(item, lines[index].replace(/^\s*\d+[.)]\s+/, '')); list.appendChild(item); index++; }
                fragment.appendChild(list); continue;
            }
            if (line.startsWith('>')) { const quote = document.createElement('blockquote'); appendInlineMarkdown(quote, line.replace(/^>\s?/, '')); fragment.appendChild(quote); index++; continue; }
            if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) { fragment.appendChild(document.createElement('hr')); index++; continue; }
            const paragraph = [];
            while (index < lines.length && lines[index].trim() && !lines[index].startsWith('```') && !/^(#{1,6})\s+/.test(lines[index]) && !/^\s*[-*+]\s+/.test(lines[index]) && !/^\s*\d+[.)]\s+/.test(lines[index]) && !lines[index].startsWith('>')) paragraph.push(lines[index++]);
            const node = document.createElement('p');
            paragraph.forEach((text, position) => { if (position) node.appendChild(document.createElement('br')); appendInlineMarkdown(node, text); });
            fragment.appendChild(node);
        }
        container.replaceChildren(fragment);
    } catch (error) {
        console.warn('Markdown rendering failed; falling back to plain text.', error);
        container.textContent = String(markdown ?? '');
    }
}

function appendInlineMarkdown(container, text) {
    const pattern = /(\*\*[^*]+\*\*|__[^_]+__|`[^`]+`|\[[^\]]+\]\([^\s)]+\)|\*[^*]+\*|_[^_]+_)/g;
    let cursor = 0;
    for (const match of text.matchAll(pattern)) {
        container.append(document.createTextNode(text.slice(cursor, match.index)));
        const token = match[0];
        if ((token.startsWith('**') && token.endsWith('**')) || (token.startsWith('__') && token.endsWith('__'))) { const strong = document.createElement('strong'); strong.textContent = token.slice(2, -2); container.appendChild(strong); }
        else if (token.startsWith('`')) { const code = document.createElement('code'); code.textContent = token.slice(1, -1); container.appendChild(code); }
        else if (token.startsWith('[')) { const end = token.indexOf(']('); const label = token.slice(1, end); const href = token.slice(end + 2, -1); const link = document.createElement('a'); link.textContent = label; if (/^(https?:|mailto:)/i.test(href)) { link.href = href; link.target = '_blank'; link.rel = 'noopener noreferrer'; } container.appendChild(link); }
        else { const emphasis = document.createElement('em'); emphasis.textContent = token.slice(1, -1); container.appendChild(emphasis); }
        cursor = match.index + token.length;
    }
    container.append(document.createTextNode(text.slice(cursor)));
}

function appendMessage(role, content, references = []) {
    const list = $('#message-list'); const item = document.createElement('article'); item.className = `message ${role === 'USER' ? 'user-message' : 'assistant-message'}`;
    const label = role === 'USER' ? '你' : 'VeriRAG';
    item.innerHTML = `<span class="message-role">${label}</span><div class="message-content"></div>`;
    const messageContent = item.querySelector('.message-content');
    if (role === 'ASSISTANT') renderMarkdown(messageContent, content || '');
    else messageContent.textContent = content || '';
    renderReferences(item, references); list.appendChild(item); list.scrollTop = list.scrollHeight;
    return item;
}

function renderReferences(messageItem, references) {
    messageItem.querySelector('.references')?.remove();
    if (!references?.length) return;
    const refs = document.createElement('details'); refs.className = 'references';
    refs.innerHTML = `<summary>引用来源（${references.length}）</summary>`;
    references.forEach((ref, index) => {
        const row = document.createElement('div'); const title = document.createElement(ref.docId ? 'button' : 'strong');
        title.textContent = `[${index + 1}] ${ref.title || '无标题'}`;
        if (ref.docId) { title.className = 'reference-link'; title.type = 'button'; title.dataset.reference = JSON.stringify(ref); }
        const content = document.createElement('p'); content.textContent = ref.content || ref.snippet || '';
        row.append(title, content); refs.appendChild(row);
    });
    messageItem.appendChild(refs);
}

async function openReferenceDetail(reference) {
    $('#reference-title').textContent = reference.title || '引用详情';
    $('#reference-snippet').textContent = reference.content || reference.snippet || ''; $('#reference-file-name').textContent = '正在加载文档信息…'; $('#reference-dialog').showModal();
    try {
        const document = await request(`/api/documents/${reference.docId}`);
        $('#reference-title').textContent = document.title || reference.title || '引用详情'; $('#reference-file-name').textContent = document.fileName || '';
    } catch (error) { $('#reference-file-name').textContent = `无法获取文档详情：${error.message}`; }
}

async function askQuestion(event) {
    event.preventDefault(); const question = $('#chat-question').value.trim(); if (!question) return;
    const selectedCategories = [...document.querySelectorAll('.filter-chip.active[data-category]')].map(b => b.dataset.category).filter(Boolean).map(Number);
    $('#chat-empty').classList.add('hidden'); appendMessage('USER', question); $('#chat-question').value = '';
    const button = $('#chat-send'); button.disabled = true; button.textContent = '思考中…';
    const assistantItem = appendMessage('ASSISTANT', ''); const content = assistantItem.querySelector('.message-content'); content.textContent = '正在思考'; content.classList.add('thinking'); let answer = '';
    try {
        await streamRequest('/api/chat/ask/stream', {question, sessionId: state.activeSessionId, categoryIds: selectedCategories}, (eventName, data) => {
            if (eventName === 'meta') state.activeSessionId = data.sessionId;
            if (eventName === 'chunk') { answer += data.content || ''; content.classList.remove('thinking'); content.textContent = answer; $('#message-list').scrollTop = $('#message-list').scrollHeight; }
            if (eventName === 'done') { state.activeSessionId = data.sessionId; content.classList.remove('thinking'); renderMarkdown(content, answer); renderReferences(assistantItem, data.references || []); loadSessions(); }
        });
    }
    catch (error) { content.classList.remove('thinking'); content.textContent = `请求失败：${error.message}`; }
    finally { button.disabled = false; button.textContent = '发送'; }
}

async function loadUsers() {
    if (!isAdmin()) return;
    try { const query = new URLSearchParams({keyword: $('#keyword').value.trim(), page: state.userPage, size: state.userSize}); const result = await request(`/api/users/page?${query}`); state.userTotal = result.total; renderUsers(result.records); const pages = Math.max(Math.ceil(result.total / state.userSize), 1); $('#pagination-info').textContent = `共 ${result.total} 位用户`; $('#page-number').textContent = `${state.userPage} / ${pages}`; $('#prev-page').disabled = state.userPage <= 1; $('#next-page').disabled = state.userPage >= pages; } catch (error) { showToast(error.message); }
}
function renderUsers(records) {
    const body = $('#user-table-body'); body.innerHTML = records.map(user => { const tr = document.createElement('tr'); tr.dataset.user = JSON.stringify(user); tr.innerHTML = `<td><div class="user-cell">${avatarMarkup(user)}<span class="username">${escapeHtml(user.username)}</span></div></td><td>${escapeHtml(user.realName || '-')}</td><td><span class="role-tag ${user.role === 'ADMIN' ? 'admin' : 'user'}">${user.role}</span></td><td><span class="status-tag ${user.status === 1 ? 'active' : 'disabled'}">${user.status === 1 ? '正常' : '禁用'}</span></td><td>${formatDate(user.createTime)}</td><td><div class="row-actions"><button class="text-button" data-user-action="edit">编辑</button><button class="text-button danger" data-user-action="delete">删除</button></div></td>`; return tr.outerHTML; }).join(''); $('#empty-state').classList.toggle('hidden', records.length !== 0);
}
function openUserDialog(user = null) { $('#user-form').reset(); $('#dialog-error').textContent = ''; $('#user-id').value = user?.id || ''; $('#dialog-title').textContent = user ? '编辑用户' : '新增用户'; $('#user-username').value = user?.username || ''; $('#user-real-name').value = user?.realName || ''; $('#user-role').value = user?.role || 'USER'; $('#user-status').value = String(user?.status ?? 1); $('#user-password').required = !user; $('#user-dialog').showModal(); }
async function saveUser(event) { event.preventDefault(); const id = $('#user-id').value; const payload = {id: id ? Number(id) : null, username: $('#user-username').value.trim(), realName: $('#user-real-name').value.trim(), role: $('#user-role').value, status: Number($('#user-status').value), password: $('#user-password').value || null}; try { await request('/api/users', {method: 'POST', body: JSON.stringify(payload)}); $('#user-dialog').close(); showToast('用户已保存'); loadUsers(); } catch (error) { $('#dialog-error').textContent = error.message; } }

async function uploadAvatar(event) { const [file] = event.target.files; if (!file) return; const form = new FormData(); form.append('file', file); try { state.user.avatar = await request('/api/users/me/avatar', {method: 'POST', body: form}); localStorage.setItem('veri-rag-user', JSON.stringify(state.user)); renderCurrentUser(); showToast('头像已更新'); } catch (error) { showToast(error.message); } finally { event.target.value = ''; } }
async function saveProfile(event) { event.preventDefault(); try { await request('/api/users/me/profile', {method: 'PUT', body: JSON.stringify({realName: $('#profile-real-name').value.trim()})}); await refreshCurrentUser(); showToast('资料已保存'); } catch (error) { showToast(error.message); } }
async function changePassword(event) { event.preventDefault(); const query = new URLSearchParams({oldPassword: $('#old-password').value, newPassword: $('#new-password').value}); try { await request(`/api/users/me/password?${query}`, {method: 'PUT'}); event.target.reset(); showToast('密码已更新'); } catch (error) { showToast(error.message); } }

$('#login-form').addEventListener('submit', login); $('#logout-button').addEventListener('click', () => logout()); $('#avatar-upload-button').addEventListener('click', () => $('#avatar-input').click()); $('#avatar-input').addEventListener('change', uploadAvatar);
document.querySelectorAll('.nav-item').forEach(item => item.addEventListener('click', () => showView(item.dataset.view)));
$('#new-chat-button').addEventListener('click', newChat); $('#chat-form').addEventListener('submit', askQuestion);
$('#message-list').addEventListener('click', event => { const link = event.target.closest('.reference-link'); if (link) openReferenceDetail(JSON.parse(link.dataset.reference)); });
$('#session-list').addEventListener('click', async event => { const deleteButton = event.target.closest('[data-delete-session]'); if (deleteButton) { event.stopPropagation(); const id = deleteButton.dataset.deleteSession; if (!confirm('确定删除这个会话吗？')) return; try { await request(`/api/chat/sessions/${id}`, {method: 'DELETE'}); if (state.activeSessionId === Number(id)) newChat(); showToast('会话已删除'); loadSessions(); } catch (error) { showToast(error.message); } return; } const item = event.target.closest('.session-item'); if (item) openSession(item.dataset.id); });
$('#chat-category-filter').addEventListener('click', event => { const chip = event.target.closest('.filter-chip'); if (!chip) return; document.querySelectorAll('.filter-chip').forEach(button => button.classList.remove('active')); chip.classList.add('active'); });
$('#new-category-button').addEventListener('click', () => $('#category-dialog').showModal()); $('#category-form').addEventListener('submit', saveCategory); $('#category-list').addEventListener('click', async event => { const deleteButton = event.target.closest('.delete-category'); if (deleteButton) { if (!confirm('确定删除该分类吗？')) return; try { await request(`/api/categories/${deleteButton.dataset.id}`, {method: 'DELETE'}); showToast('分类已删除'); await loadCategories(); if (state.view === 'knowledge') loadDocuments(); } catch (error) { showToast(error.message); } return; } const categoryButton = event.target.closest('.category-select'); if (!categoryButton) return; $('#document-category').value = categoryButton.dataset.categoryId; state.documentPage = 1; state.selectedDocumentIds.clear(); renderCategories(); await loadDocuments(); });
$('#upload-document-button').addEventListener('click', () => $('#document-dialog').showModal()); $('#document-form').addEventListener('submit', uploadDocument); $('#document-search-button').addEventListener('click', () => { state.documentPage = 1; loadDocuments(); }); $('#document-category').addEventListener('change', () => { state.documentPage = 1; loadDocuments(); }); $('#document-prev').addEventListener('click', () => { if (state.documentPage > 1) { state.documentPage--; loadDocuments(); } }); $('#document-next').addEventListener('click', () => { if (state.documentPage * state.documentSize < state.documentTotal) { state.documentPage++; loadDocuments(); } }); $('#batch-reingest-button').addEventListener('click', batchReingestDocuments); $('#document-list').addEventListener('change', event => { const checkbox = event.target.closest('.document-select'); if (!checkbox) return; const id = Number(checkbox.dataset.id); if (checkbox.checked) state.selectedDocumentIds.add(id); else state.selectedDocumentIds.delete(id); updateBatchReingestButton(); }); $('#document-list').addEventListener('click', async event => { const reingest = event.target.closest('.reingest-document'); if (reingest) { if (!confirm(`确定重新向量化文档“${reingest.dataset.title}”吗？`)) return; reingest.disabled = true; reingest.textContent = '处理中…'; try { await request(`/api/documents/${reingest.dataset.id}/reingest`, {method: 'POST'}); showToast('文档已重新向量化'); loadDocuments(); } catch (error) { showToast(error.message); reingest.disabled = false; reingest.textContent = '重新向量化'; } return; } const button = event.target.closest('.delete-document'); if (!button || !confirm(`确定删除文档“${button.dataset.title}”吗？`)) return; try { await request(`/api/documents/${button.dataset.id}`, {method: 'DELETE'}); state.selectedDocumentIds.delete(Number(button.dataset.id)); showToast('文档和向量已删除'); loadDocuments(); } catch (error) { showToast(error.message); } });
$('#search-button').addEventListener('click', () => { state.userPage = 1; loadUsers(); }); $('#create-button').addEventListener('click', () => openUserDialog()); $('#prev-page').addEventListener('click', () => { if (state.userPage > 1) { state.userPage--; loadUsers(); } }); $('#next-page').addEventListener('click', () => { if (state.userPage * state.userSize < state.userTotal) { state.userPage++; loadUsers(); } }); $('#user-form').addEventListener('submit', saveUser); $('#close-dialog').addEventListener('click', () => $('#user-dialog').close()); $('#cancel-dialog').addEventListener('click', () => $('#user-dialog').close()); $('#user-table-body').addEventListener('click', async event => { const button = event.target.closest('[data-user-action]'); if (!button) return; const user = JSON.parse(button.closest('tr').dataset.user); if (button.dataset.userAction === 'edit') return openUserDialog(user); if (!confirm(`确定删除用户“${user.username}”吗？`)) return; try { await request(`/api/users/${user.id}`, {method: 'DELETE'}); showToast('用户已删除'); loadUsers(); } catch (error) { showToast(error.message); } });
$('#profile-form').addEventListener('submit', saveProfile); $('#password-form').addEventListener('submit', changePassword); document.querySelectorAll('[data-close-dialog]').forEach(button => button.addEventListener('click', () => $(`#${button.dataset.closeDialog}`).close()));
if (state.token) showConsole();
