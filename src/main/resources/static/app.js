const API_BASE = document.querySelector('meta[name="api-base"]').content.replace(/\/$/, '');
const state = { token: localStorage.getItem('veri-rag-token'), user: null, page: 1, size: 10, total: 0 };

const $ = (selector) => document.querySelector(selector);
const loginView = $('#login-view');
const consoleView = $('#console-view');
const dialog = $('#user-dialog');

async function request(path, options = {}) {
    const isFormData = options.body instanceof FormData;
    const headers = { ...(options.body && !isFormData ? { 'Content-Type': 'application/json' } : {}), ...(options.headers || {}) };
    if (state.token) headers.Authorization = `Bearer ${state.token}`;
    const response = await fetch(`${API_BASE}${path}`, { ...options, headers });
    const payload = await response.json().catch(() => ({ code: response.status, message: '服务器返回了无效响应' }));
    if (!response.ok || payload.code !== 200) {
        if (response.status === 401 || payload.code === 401) logout(false);
        throw new Error(payload.message || '请求失败');
    }
    return payload.data;
}

function showToast(message) {
    const toast = $('#toast'); toast.textContent = message; toast.classList.add('show');
    window.clearTimeout(showToast.timer); showToast.timer = window.setTimeout(() => toast.classList.remove('show'), 2400);
}

function formatDate(value) {
    if (!value) return '-';
    return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

function avatarUrl(relativePath) {
    const encodedPath = relativePath.split('/').map(encodeURIComponent).join('/');
    return `${API_BASE}/files/${encodedPath}`;
}

function initials(user) {
    return (user.realName || user.username || '?').trim().slice(0, 1).toUpperCase();
}

function avatarMarkup(user, extraClass = '') {
    const classes = `avatar ${extraClass}`.trim();
    return user.avatar
        ? `<span class="${classes}"><img src="${avatarUrl(user.avatar)}" alt="${escapeHtml(user.username)} 的头像"></span>`
        : `<span class="${classes}">${escapeHtml(initials(user))}</span>`;
}

function renderCurrentUser() {
    const user = state.user;
    const avatar = $('#current-avatar');
    avatar.replaceChildren(); avatar.textContent = initials(user);
    if (user.avatar) {
        const image = new Image(); image.src = avatarUrl(user.avatar); image.alt = '';
        image.onerror = () => { avatar.replaceChildren(); avatar.textContent = initials(user); };
        avatar.replaceChildren(image);
    }
    $('#current-user-name').textContent = `${user.realName || user.username} · ${user.role}`;
}

function setLoginError(message = '') { $('#login-error').textContent = message; }

async function login(event) {
    event.preventDefault(); setLoginError();
    const submit = $('#login-submit'); submit.disabled = true; submit.textContent = '正在登录…';
    try {
        const data = await request('/api/auth/login', { method: 'POST', body: JSON.stringify({ username: $('#login-username').value.trim(), password: $('#login-password').value }) });
        state.token = data.token; state.user = data.user; localStorage.setItem('veri-rag-token', data.token); localStorage.setItem('veri-rag-user', JSON.stringify(data.user));
        showConsole();
    } catch (error) { setLoginError(error.message); }
    finally { submit.disabled = false; submit.textContent = '登录'; }
}

function logout(showMessage = true) {
    state.token = null; state.user = null; localStorage.removeItem('veri-rag-token'); localStorage.removeItem('veri-rag-user');
    consoleView.classList.add('hidden'); loginView.classList.remove('hidden'); $('#login-form').reset();
    if (showMessage) showToast('已退出登录');
}

function showConsole() {
    loginView.classList.add('hidden'); consoleView.classList.remove('hidden');
    const user = state.user || JSON.parse(localStorage.getItem('veri-rag-user') || 'null'); state.user = user;
    renderCurrentUser();
    const isAdmin = user.role === 'ADMIN'; $('#user-management').classList.toggle('hidden', !isAdmin); $('#permission-notice').classList.toggle('hidden', isAdmin);
    if (isAdmin) loadUsers();
    refreshCurrentUser();
}

async function refreshCurrentUser() {
    try {
        state.user = await request('/api/users/me');
        localStorage.setItem('veri-rag-user', JSON.stringify(state.user));
        renderCurrentUser();
    } catch (error) {
        if (state.token) showToast(error.message);
    }
}

function renderUsers(records) {
    const body = $('#user-table-body'); body.innerHTML = '';
    records.forEach(user => {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td><div class="user-cell">${avatarMarkup(user)}<span class="username">${escapeHtml(user.username)}</span></div></td><td>${escapeHtml(user.realName || '-')}</td><td><span class="role-tag ${user.role === 'ADMIN' ? 'admin' : 'user'}">${user.role}</span></td><td><span class="status-tag ${user.status === 1 ? 'active' : 'disabled'}">${user.status === 1 ? '正常' : '禁用'}</span></td><td>${formatDate(user.createTime)}</td><td><div class="row-actions"><button class="text-button" data-action="edit" data-id="${user.id}">编辑</button><button class="text-button danger" data-action="delete" data-id="${user.id}">删除</button></div></td>`;
        tr.dataset.user = JSON.stringify(user); body.appendChild(tr);
    });
    $('#empty-state').classList.toggle('hidden', records.length !== 0);
}

async function loadUsers() {
    try {
        const query = new URLSearchParams({ keyword: $('#keyword').value.trim(), page: state.page, size: state.size });
        const result = await request(`/api/users/page?${query}`); state.total = result.total; renderUsers(result.records);
        const pages = Math.max(Math.ceil(state.total / state.size), 1); $('#pagination-info').textContent = `共 ${state.total} 位用户`;
        $('#page-number').textContent = `${state.page} / ${pages}`; $('#prev-page').disabled = state.page <= 1; $('#next-page').disabled = state.page >= pages;
    } catch (error) { showToast(error.message); }
}

function openUserDialog(user = null) {
    $('#user-form').reset(); $('#dialog-error').textContent = ''; $('#user-id').value = user?.id || ''; $('#dialog-title').textContent = user ? '编辑用户' : '新增用户';
    $('#user-username').value = user?.username || ''; $('#user-real-name').value = user?.realName || ''; $('#user-role').value = user?.role || 'USER'; $('#user-status').value = String(user?.status ?? 1); $('#user-password').required = !user;
    dialog.showModal(); $('#user-username').focus();
}

async function saveUser(event) {
    event.preventDefault(); $('#dialog-error').textContent = '';
    const id = $('#user-id').value;
    const payload = { id: id ? Number(id) : null, username: $('#user-username').value.trim(), realName: $('#user-real-name').value.trim(), role: $('#user-role').value, status: Number($('#user-status').value), password: $('#user-password').value || null };
    try { await request('/api/users', { method: 'POST', body: JSON.stringify(payload) }); dialog.close(); showToast('用户已保存'); loadUsers(); }
    catch (error) { $('#dialog-error').textContent = error.message; }
}

async function uploadAvatar(event) {
    const [file] = event.target.files;
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) { showToast('头像不能超过 5 MB'); event.target.value = ''; return; }
    const data = new FormData(); data.append('file', file);
    try {
        const relativePath = await request('/api/users/me/avatar', { method: 'POST', body: data });
        state.user.avatar = relativePath;
        localStorage.setItem('veri-rag-user', JSON.stringify(state.user));
        renderCurrentUser();
        showToast('头像已更新');
        if (state.user.role === 'ADMIN') loadUsers();
    } catch (error) { showToast(error.message); }
    finally { event.target.value = ''; }
}

function escapeHtml(value) { const div = document.createElement('div'); div.textContent = value; return div.innerHTML; }

$('#login-form').addEventListener('submit', login);
$('#logout-button').addEventListener('click', () => logout());
$('#avatar-upload-button').addEventListener('click', () => $('#avatar-input').click());
$('#avatar-input').addEventListener('change', uploadAvatar);
$('#search-button').addEventListener('click', () => { state.page = 1; loadUsers(); });
$('#keyword').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); state.page = 1; loadUsers(); } });
$('#create-button').addEventListener('click', () => openUserDialog());
$('#close-dialog').addEventListener('click', () => dialog.close()); $('#cancel-dialog').addEventListener('click', () => dialog.close()); $('#user-form').addEventListener('submit', saveUser);
$('#prev-page').addEventListener('click', () => { if (state.page > 1) { state.page--; loadUsers(); } }); $('#next-page').addEventListener('click', () => { if (state.page * state.size < state.total) { state.page++; loadUsers(); } });
$('#user-table-body').addEventListener('click', async event => {
    const button = event.target.closest('button[data-action]'); if (!button) return;
    const user = JSON.parse(button.closest('tr').dataset.user);
    if (button.dataset.action === 'edit') return openUserDialog(user);
    if (!confirm(`确定删除用户“${user.username}”吗？`)) return;
    try { await request(`/api/users/${user.id}`, { method: 'DELETE' }); showToast('用户已删除'); if ($('#user-table-body').children.length === 1 && state.page > 1) state.page--; loadUsers(); }
    catch (error) { showToast(error.message); }
});

if (state.token) { showConsole(); }
