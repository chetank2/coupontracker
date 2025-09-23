(function(global) {
    const apiBase = '/orchestrator';

    async function fetchJSON(url, options) {
        const response = await fetch(url, options);
        if (!response.ok) {
            const text = await response.text();
            throw new Error(text || response.statusText);
        }
        return response.json();
    }

    async function listJobs() {
        return fetchJSON(`/api/orchestrator/jobs`);
    }

    async function submitJob(configPath, notes) {
        return fetchJSON(`/api/orchestrator/jobs`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ config_path: configPath, notes })
        });
    }

    global.OrchestratorAPI = { listJobs, submitJob };
})(window);
