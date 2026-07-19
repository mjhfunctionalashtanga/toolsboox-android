<?php
/**
 * Plugin Name: Ledgr FluentBoards Bridge
 * Plugin URI: https://ledgr.cc
 * Description: E-ink handwriting bridge for FluentBoards. One-shot handwritten card capture (stroke PNG as cover + raw strokes archived), async OCR write-back with FluentCRM contact matching, compact board payloads for e-ink clients, and stroke round-tripping.
 * Version: 0.1.0
 * Author: Michael Joel Hall
 * License: GPLv3
 * Requires Plugins: fluent-boards
 */

if (!defined('ABSPATH')) {
    exit;
}

define('LEDGR_FB_BRIDGE_VERSION', '0.1.0');

/**
 * Adapter so FluentBoards' FileSystem::put() (which expects framework
 * File objects with toArray() + getRealPath()) can consume a standard
 * WP REST $_FILES-shaped array. Lets us reuse FB's exact upload path:
 * wp-content/uploads/fluent-boards/board_{id}/{timestamp}-{name}
 */
class Ledgr_FB_File_Adapter
{
    private $file;

    public function __construct(array $file)
    {
        $this->file = $file;
    }

    public function toArray()
    {
        return $this->file;
    }

    public function getRealPath()
    {
        return $this->file['tmp_name'];
    }
}

class Ledgr_FB_Bridge
{
    const NS = 'ledgr/v1';
    const SOURCE = 'ledgr';
    const META_STROKES = 'ledgr_strokes';
    const META_OCR = 'ledgr_ocr';
    const MAX_PNG_BYTES = 8388608;      // 8 MB
    const MAX_STROKE_BYTES = 16777216;  // 16 MB gzipped JSON
    const MAX_VIDEO_BYTES = 134217728;  // 128 MB direct clip; use video_url (Bunny) beyond

    public function __construct()
    {
        add_action('rest_api_init', [$this, 'routes']);
        add_action('fluent_boards/task_stage_updated', [$this, 'onStageUpdated'], 20, 2);

        // RSS → community spaces pull (map lives in option ledgr_fb_rss_map).
        add_action('ledgr_fb_rss_pull', [$this, 'pullRssFeeds']);
        if (!wp_next_scheduled('ledgr_fb_rss_pull') && get_option('ledgr_fb_rss_map')) {
            wp_schedule_event(time() + 300, 'hourly', 'ledgr_fb_rss_pull');
        }
    }

    public static function boot()
    {
        if (!class_exists('\FluentBoards\App\Models\Task')) {
            add_action('admin_notices', function () {
                echo '<div class="notice notice-error"><p>Ledgr FB Bridge requires FluentBoards to be active.</p></div>';
            });
            return;
        }
        new self();
    }

    /* ---------------------------------------------------------------
     * Routes
     * ------------------------------------------------------------- */

    public function routes()
    {
        register_rest_route(self::NS, '/card', [
            'methods'             => 'POST',
            'callback'            => [$this, 'createCard'],
            'permission_callback' => [$this, 'canWrite'],
        ]);

        register_rest_route(self::NS, '/card/(?P<note_uuid>[a-zA-Z0-9\-_]+)/ocr', [
            'methods'             => 'PATCH',
            'callback'            => [$this, 'landOcr'],
            'permission_callback' => [$this, 'canWrite'],
        ]);

        register_rest_route(self::NS, '/board/(?P<board_id>\d+)/compact', [
            'methods'             => 'GET',
            'callback'            => [$this, 'compactBoard'],
            'permission_callback' => [$this, 'canReadBoard'],
        ]);

        // Site-boards browser: every board the current user can touch.
        register_rest_route(self::NS, '/boards', [
            'methods'             => 'GET',
            'callback'            => [$this, 'listBoards'],
            'permission_callback' => [$this, 'canWrite'],
        ]);

        // Tactile move: drag a card to a stage and it lands on the real board.
        register_rest_route(self::NS, '/board/(?P<board_id>\d+)/task/(?P<task_id>\d+)/move', [
            'methods'             => 'POST',
            'callback'            => [$this, 'moveTask'],
            'permission_callback' => [$this, 'canReadBoard'],
        ]);

        // Deep detail: the full task behind a card (assignees, labels, comments, due).
        register_rest_route(self::NS, '/board/(?P<board_id>\d+)/task/(?P<task_id>\d+)', [
            'methods'             => 'GET',
            'callback'            => [$this, 'taskDetail'],
            'permission_callback' => [$this, 'canReadBoard'],
        ]);

        // Add a comment (typed or handwritten) to a card — the reply loop.
        register_rest_route(self::NS, '/board/(?P<board_id>\d+)/task/(?P<task_id>\d+)/comment', [
            'methods'             => 'POST',
            'callback'            => [$this, 'commentTask'],
            'permission_callback' => [$this, 'canReadBoard'],
        ]);

        // The board's people — who a card can be assigned to.
        register_rest_route(self::NS, '/board/(?P<board_id>\d+)/members', [
            'methods'             => 'GET',
            'callback'            => [$this, 'boardMembers'],
            'permission_callback' => [$this, 'canReadBoard'],
        ]);

        // Edit a card from the device: assignees, due date, priority.
        register_rest_route(self::NS, '/board/(?P<board_id>\d+)/task/(?P<task_id>\d+)/update', [
            'methods'             => 'POST',
            'callback'            => [$this, 'updateTask'],
            'permission_callback' => [$this, 'canReadBoard'],
        ]);

        register_rest_route(self::NS, '/card/(?P<task_id>\d+)/strokes', [
            'methods'             => 'GET',
            'callback'            => [$this, 'getStrokes'],
            'permission_callback' => [$this, 'canWrite'],
        ]);

        register_rest_route(self::NS, '/crm/note', [
            'methods'             => 'POST',
            'callback'            => [$this, 'createCrmNote'],
            'permission_callback' => [$this, 'canWrite'],
        ]);

        register_rest_route(self::NS, '/crm/contact/(?P<contact_id>\d+)/compact', [
            'methods'             => 'GET',
            'callback'            => [$this, 'compactContact'],
            'permission_callback' => [$this, 'canWrite'],
        ]);

        register_rest_route(self::NS, '/community/ingest', [
            'methods'             => 'POST',
            'callback'            => [$this, 'ingestFeedItems'],
            'permission_callback' => [$this, 'canWrite'],
        ]);

        register_rest_route(self::NS, '/community/comment', [
            'methods'             => 'POST',
            'callback'            => [$this, 'createCommunityComment'],
            'permission_callback' => [$this, 'canCommunity'],
        ]);

        register_rest_route(self::NS, '/correspondence', [
            'methods'             => 'GET',
            'callback'            => [$this, 'correspondence'],
            'permission_callback' => [$this, 'canCommunity'],
        ]);

        // Correspondence Inbox: land replies on your shared items as cards on a board.
        register_rest_route(self::NS, '/correspondence/to-board', [
            'methods'             => 'POST',
            'callback'            => [$this, 'correspondenceToBoard'],
            'permission_callback' => [$this, 'canReadBoard'],
        ]);

        register_rest_route(self::NS, '/person/compact', [
            'methods'             => 'GET',
            'callback'            => [$this, 'compactPerson'],
            'permission_callback' => [$this, 'canWrite'],
        ]);

        register_rest_route(self::NS, '/essay', [
            'methods'             => 'POST',
            'callback'            => [$this, 'createEssay'],
            'permission_callback' => [$this, 'canWrite'],
        ]);

        register_rest_route(self::NS, '/community/gram', [
            'methods'             => 'POST',
            'callback'            => [$this, 'createGram'],
            'permission_callback' => [$this, 'canCommunity'],
        ]);

        register_rest_route(self::NS, '/community/spaces', [
            'methods'             => 'GET',
            'callback'            => [$this, 'listSpaces'],
            'permission_callback' => [$this, 'canCommunity'],
        ]);

        register_rest_route(self::NS, '/community/feed', [
            'methods'             => 'GET',
            'callback'            => [$this, 'spaceFeed'],
            'permission_callback' => [$this, 'canCommunity'],
        ]);

        register_rest_route(self::NS, '/community/courses', [
            'methods'             => 'GET',
            'callback'            => [$this, 'listCourses'],
            'permission_callback' => [$this, 'canCommunity'],
        ]);

        register_rest_route(self::NS, '/community/course/(?P<course_id>\d+)/lessons', [
            'methods'             => 'GET',
            'callback'            => [$this, 'courseLessons'],
            'permission_callback' => [$this, 'canCommunity'],
        ]);
    }

    /* ---------------------------------------------------------------
     * Permissions — WP Application Passwords (Basic auth) set the
     * current user on REST requests, matching FluentBoards' own
     * AuthPolicy (get_current_user_id + hasAppAccess).
     * ------------------------------------------------------------- */

    public function canWrite()
    {
        return is_user_logged_in()
            && \FluentBoards\App\Services\PermissionManager::hasAppAccess();
    }

    /** Community surfaces are for MEMBERS: any logged-in user. Space membership is still
     *  enforced per-post (assertSpaceMembership), and boards/CRM/essay stay staff-only. */
    public function canCommunity()
    {
        return is_user_logged_in();
    }

    public function canReadBoard($request)
    {
        if (!$this->canWrite()) {
            return false;
        }
        $boardId = (int) $request['board_id'];
        return \FluentBoards\App\Services\PermissionManager::isAdmin()
            || \FluentBoards\App\Services\PermissionManager::userHasBoardAccess($boardId, get_current_user_id());
    }

    /* ---------------------------------------------------------------
     * POST /ledgr/v1/card
     * multipart: png (file), strokes (file, optional .json.gz),
     *            board_id, stage_id, note_uuid, ocr_title (optional)
     * ------------------------------------------------------------- */

    public function createCard(\WP_REST_Request $request)
    {
        $boardId  = (int) $request->get_param('board_id');
        $stageId  = (int) $request->get_param('stage_id');
        $noteUuid = sanitize_text_field($request->get_param('note_uuid'));
        $ocrTitle = sanitize_text_field($request->get_param('ocr_title') ?: '');

        if (!$boardId || !$stageId || !$noteUuid) {
            return new \WP_Error('ledgr_bad_request', 'board_id, stage_id and note_uuid are required', ['status' => 400]);
        }

        // Idempotency: same note_uuid returns the existing task.
        $existing = \FluentBoards\App\Models\Task::where('source', self::SOURCE)
            ->where('source_id', $noteUuid)
            ->first();

        if ($existing) {
            return rest_ensure_response([
                'task_id'   => $existing->id,
                'cover_url' => $this->coverUrl($existing),
                'existing'  => true,
            ]);
        }

        $files = $request->get_file_params();
        $png   = isset($files['png']) ? $files['png'] : null;

        if ($png) {
            $err = $this->validateUpload($png, ['image/png', 'image/jpeg', 'image/webp'], self::MAX_PNG_BYTES);
            if (is_wp_error($err)) {
                return $err;
            }
        }

        try {
            $taskService = new \FluentBoards\App\Services\TaskService();

            $task = $taskService->createTask([
                'title'     => $ocrTitle ?: ('Handwritten card ' . substr($noteUuid, 0, 8)),
                'board_id'  => $boardId,
                'stage_id'  => $stageId,
                'source'    => self::SOURCE,
                'source_id' => $noteUuid,
            ], $boardId);

            // FB 1.9x decorates createTask's return with computed props (isOverdue, …)
            // that a later save() would try to persist as columns. Re-fetch clean.
            $task = \FluentBoards\App\Models\Task::find($task->id);

            $coverUrl = null;

            if ($png) {
                $coverUrl = $this->attachCover($task, $boardId, $png);
            }

            if (isset($files['strokes'])) {
                $this->storeStrokes($task, $boardId, $files['strokes']);
            }

            return rest_ensure_response([
                'task_id'   => $task->id,
                'stage_id'  => (int) $task->stage_id,
                'position'  => $task->position,
                'cover_url' => $coverUrl,
                'existing'  => false,
            ]);
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_create_failed', $e->getMessage(), ['status' => 400]);
        }
    }

    /* ---------------------------------------------------------------
     * PATCH /ledgr/v1/card/{note_uuid}/ocr
     * json: { text, entities: { emails: [], names: [] }, confidence }
     * ------------------------------------------------------------- */

    public function landOcr(\WP_REST_Request $request)
    {
        $noteUuid = sanitize_text_field($request['note_uuid']);

        $task = \FluentBoards\App\Models\Task::where('source', self::SOURCE)
            ->where('source_id', $noteUuid)
            ->first();

        if (!$task) {
            return new \WP_Error('ledgr_not_found', 'No Ledgr card with that note_uuid', ['status' => 404]);
        }

        $body       = $request->get_json_params();
        $text       = isset($body['text']) ? wp_kses_post($body['text']) : '';
        $entities   = isset($body['entities']) && is_array($body['entities']) ? $body['entities'] : [];
        $confidence = isset($body['confidence']) ? (float) $body['confidence'] : 0;

        $taskService = new \FluentBoards\App\Services\TaskService();

        // Title: first non-empty OCR line, if the task still has the placeholder.
        $firstLine = trim(strtok(wp_strip_all_tags($text), "\n"));
        if ($firstLine && strpos($task->title, 'Handwritten card ') === 0) {
            $taskService->updateTaskProperty('title', wp_trim_words($firstLine, 12, ''), $task);
        }

        // Description: full OCR text (FB descriptions accept HTML).
        if ($text) {
            $html = nl2br(esc_html(wp_strip_all_tags($text)));
            $taskService->updateTaskProperty('description', $html, $task);
        }

        // CRM match — exact email only for auto-association.
        $match = $this->matchCrmContact($entities);
        if ($match && empty($task->crm_contact_id)) {
            $taskService->updateTaskProperty('crm_contact_id', $match['id'], $task);
        }

        $this->setTaskMeta($task->id, self::META_OCR, [
            'text'       => wp_strip_all_tags($text),
            'entities'   => $entities,
            'confidence' => $confidence,
            'crm_match'  => $match,
            'landed_at'  => current_time('mysql'),
        ]);

        return rest_ensure_response([
            'task_id'        => $task->id,
            'title'          => $task->fresh()->title,
            'crm_contact_id' => $match ? $match['id'] : ($task->crm_contact_id ?: null),
            'crm_match'      => $match,
        ]);
    }

    /* ---------------------------------------------------------------
     * GET /ledgr/v1/board/{board_id}/compact
     * Trimmed payload + rev hash. Client skips render when rev matches.
     * ------------------------------------------------------------- */

    public function compactBoard(\WP_REST_Request $request)
    {
        $boardId = (int) $request['board_id'];

        $stages = \FluentBoards\App\Models\Stage::where('board_id', $boardId)
            ->whereNull('archived_at')
            ->orderBy('position', 'asc')
            ->get(['id', 'title', 'position', 'settings']);

        $tasks = \FluentBoards\App\Models\Task::where('board_id', $boardId)
            ->whereNull('archived_at')
            ->whereNull('parent_id')
            ->orderBy('position', 'asc')
            ->get(['id', 'title', 'stage_id', 'position', 'priority', 'status', 'due_at', 'crm_contact_id', 'settings', 'source', 'updated_at']);

        $maxUpdated = $tasks->max('updated_at');

        $rev = md5($boardId . '|' . $tasks->count() . '|' . $maxUpdated . '|' . $stages->count());

        if ($request->get_param('rev') === $rev) {
            return rest_ensure_response(['rev' => $rev, 'unchanged' => true]);
        }

        $out = [
            'rev'    => $rev,
            'stages' => [],
        ];

        foreach ($stages as $stage) {
            $out['stages'][] = [
                'id'       => $stage->id,
                'title'    => $stage->title,
                'position' => (float) $stage->position,
            ];
        }

        foreach ($tasks as $task) {
            $settings = $task->settings;
            $out['tasks'][] = [
                'id'             => $task->id,
                'title'          => $task->title,
                'stage_id'       => (int) $task->stage_id,
                'position'       => (float) $task->position,
                'priority'       => $task->priority,
                'status'         => $task->status,
                'due_at'         => $task->due_at,
                'crm_contact_id' => $task->crm_contact_id ? (int) $task->crm_contact_id : null,
                'cover_url'      => isset($settings['cover']['backgroundImage']) ? $settings['cover']['backgroundImage'] : null,
                'is_ledgr'       => $task->source === self::SOURCE,
            ];
        }

        return rest_ensure_response($out);
    }

    /* ---------------------------------------------------------------
     * GET /ledgr/v1/boards
     * Every board the current user can see — the site-boards browser.
     * Each row carries a light task/stage count so the browser can show
     * a board at a glance without pulling its whole compact payload.
     * ------------------------------------------------------------- */

    public function listBoards(\WP_REST_Request $request)
    {
        if (!class_exists('\FluentBoards\App\Models\Board')) {
            return new \WP_Error('ledgr_no_boards', 'FluentBoards is not active', ['status' => 501]);
        }

        $userId  = get_current_user_id();
        $isAdmin = \FluentBoards\App\Services\PermissionManager::isAdmin();

        $out = [];
        try {
            $boards = \FluentBoards\App\Models\Board::whereNull('archived_at')
                ->where('type', 'to-do')
                ->orderBy('title')
                ->get(['id', 'title', 'type', 'description', 'currency', 'background', 'created_at']);

            foreach ($boards as $board) {
                if (!$isAdmin
                    && !\FluentBoards\App\Services\PermissionManager::userHasBoardAccess($board->id, $userId)) {
                    continue;
                }

                $taskCount = \FluentBoards\App\Models\Task::where('board_id', $board->id)
                    ->whereNull('archived_at')
                    ->whereNull('parent_id')
                    ->count();
                $stageCount = \FluentBoards\App\Models\Stage::where('board_id', $board->id)
                    ->whereNull('archived_at')
                    ->count();

                $bg = $board->background;
                $color = null;
                if (is_array($bg) && !empty($bg['color'])) {
                    $color = $bg['color'];
                } elseif (is_string($bg) && $bg !== '') {
                    $color = $bg;
                }

                $out[] = [
                    'id'          => (int) $board->id,
                    'title'       => $board->title,
                    'type'        => $board->type,
                    'color'       => $color,
                    'task_count'  => (int) $taskCount,
                    'stage_count' => (int) $stageCount,
                ];
            }
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_boards_failed', $e->getMessage(), ['status' => 500]);
        }

        return rest_ensure_response(['boards' => $out]);
    }

    /* ---------------------------------------------------------------
     * POST /ledgr/v1/board/{board_id}/task/{task_id}/move
     * body: new_stage_id (required), new_index (optional, 1-based;
     *       0/absent = append to the end of the stage)
     * The tactile drag lands here: set the stage, reposition, and fire
     * FluentBoards' own task_stage_updated hook so notifications, email,
     * default-assignees and close-on-done all run exactly as in the web UI.
     * ------------------------------------------------------------- */

    public function moveTask(\WP_REST_Request $request)
    {
        $boardId    = (int) $request['board_id'];
        $taskId     = (int) $request['task_id'];
        $newStageId = (int) $request->get_param('new_stage_id');
        $newIndex   = (int) $request->get_param('new_index');

        if (!$newStageId) {
            return new \WP_Error('ledgr_bad_request', 'new_stage_id is required', ['status' => 400]);
        }

        try {
            $service = new \FluentBoards\App\Services\TaskService();
            $task    = $service->findTaskOnBoard($taskId, $boardId);
            if (!$task) {
                return new \WP_Error('ledgr_not_found', 'Task not found on this board', ['status' => 404]);
            }

            $targetStage = \FluentBoards\App\Models\Stage::where('id', $newStageId)
                ->where('board_id', $boardId)
                ->first();
            if (!$targetStage) {
                return new \WP_Error('ledgr_bad_stage', 'Stage is not on this board', ['status' => 400]);
            }

            $oldStageId = (int) $task->stage_id;

            // Clean stage-archive meta on a real stage change (mirrors core moveTask).
            if ($oldStageId !== $newStageId && class_exists('\FluentBoards\App\Models\TaskMeta')) {
                try {
                    \FluentBoards\App\Models\TaskMeta::where('task_id', $task->id)
                        ->where('key', 'archived_by_stage')->delete();
                } catch (\Exception $e) { /* non-fatal */ }
            }

            $task->stage_id = $newStageId;
            // Append when no explicit index: last position + 1 keeps ordering sane.
            if ($newIndex > 0) {
                $task = $task->moveToNewPosition($newIndex);
            } else {
                $last = \FluentBoards\App\Models\Task::where('stage_id', $newStageId)
                    ->whereNull('archived_at')->max('position');
                $task->position = ($last ?: 0) + 1;
                $task->save();
            }

            if ($oldStageId !== $newStageId) {
                try {
                    $service->manageDefaultAssignees($task, $newStageId);
                } catch (\Exception $e) { /* non-fatal */ }

                // Close-on-done: a stage whose default is "closed" checks the card off.
                try {
                    $defaultPosition = $task->stage ? $task->stage->defaultTaskStatus() : null;
                    if ($defaultPosition === 'closed' && $task->status !== 'closed') {
                        $task = $task->close();
                    } elseif ($defaultPosition !== 'closed' && $task->status === 'closed') {
                        // Moving out of a done column reopens the card.
                        if (method_exists($task, 'reopen')) {
                            $task = $task->reopen();
                        }
                    }
                } catch (\Exception $e) { /* non-fatal */ }

                do_action('fluent_boards/task_stage_updated', $task, $oldStageId);
            }

            do_action('fluent_boards/task_updated', $task, 'position');

            return rest_ensure_response([
                'task_id'  => (int) $task->id,
                'stage_id' => (int) $task->stage_id,
                'position' => (float) $task->position,
                'status'   => $task->status,
            ]);
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_move_failed', $e->getMessage(), ['status' => 400]);
        }
    }

    /* ---------------------------------------------------------------
     * GET /ledgr/v1/board/{board_id}/task/{task_id}
     * The full task behind a card: description, due, priority, cover,
     * assignees (name + avatar), labels, matched CRM contact, and the
     * comment thread — so tapping a card opens the real thing.
     * ------------------------------------------------------------- */

    public function taskDetail(\WP_REST_Request $request)
    {
        $boardId = (int) $request['board_id'];
        $taskId  = (int) $request['task_id'];

        try {
            $task = \FluentBoards\App\Models\Task::where('id', $taskId)
                ->where('board_id', $boardId)
                ->first();
            if (!$task) {
                return new \WP_Error('ledgr_not_found', 'Task not found on this board', ['status' => 404]);
            }

            $settings = $task->settings;

            $assignees = [];
            try {
                foreach ($task->assignees as $u) {
                    $assignees[] = [
                        'id'     => (int) $u->ID,
                        'name'   => $u->display_name,
                        'email'  => $u->user_email,
                        'avatar' => get_avatar_url($u->ID, ['size' => 96]),
                    ];
                }
            } catch (\Exception $e) { /* relation optional */ }

            $labels = [];
            try {
                foreach ($task->labels as $l) {
                    $labels[] = [
                        'id'    => (int) $l->id,
                        'title' => $l->title,
                        'color' => $l->color ?? $l->bg_color ?? null,
                    ];
                }
            } catch (\Exception $e) { /* relation optional */ }

            $comments = [];
            try {
                $rows = \FluentBoards\App\Models\Comment::where('task_id', $taskId)
                    ->orderBy('id', 'desc')->limit(30)->get();
                foreach ($rows as $c) {
                    $author = get_user_by('id', $c->created_by);
                    $body   = (string) ($c->description ?? $c->comment ?? '');
                    $comments[] = [
                        'id'         => (int) $c->id,
                        'author'     => $author ? $author->display_name : ('User ' . $c->created_by),
                        'excerpt'    => wp_trim_words(wp_strip_all_tags($body), 60),
                        'html'       => $body,
                        'created_at' => (string) $c->created_at,
                    ];
                }
            } catch (\Exception $e) { /* comments optional */ }

            $crm = null;
            if ($task->crm_contact_id && function_exists('FluentCrmApi')) {
                try {
                    $contact = FluentCrmApi('contacts')->getContact((int) $task->crm_contact_id);
                    if ($contact) {
                        $crm = [
                            'id'    => (int) $task->crm_contact_id,
                            'name'  => trim($contact->first_name . ' ' . $contact->last_name),
                            'email' => $contact->email,
                        ];
                    }
                } catch (\Exception $e) { /* crm optional */ }
            }

            return rest_ensure_response([
                'id'          => (int) $task->id,
                'board_id'    => (int) $task->board_id,
                'title'       => $task->title,
                'description' => (string) ($task->description ?? ''),
                'stage_id'    => (int) $task->stage_id,
                'priority'    => $task->priority,
                'status'      => $task->status,
                'due_at'      => $task->due_at,
                'cover_url'   => isset($settings['cover']['backgroundImage']) ? $settings['cover']['backgroundImage'] : null,
                'is_ledgr'    => $task->source === self::SOURCE,
                'source'      => $task->source,
                'source_id'   => $task->source_id,
                'assignees'   => $assignees,
                'labels'      => $labels,
                'comments'    => $comments,
                'crm'         => $crm,
            ]);
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_detail_failed', $e->getMessage(), ['status' => 500]);
        }
    }

    /* ---------------------------------------------------------------
     * POST /ledgr/v1/board/{board_id}/task/{task_id}/comment
     * multipart: text (optional), png (optional handwriting) — one or
     * both. A handwritten reply uploads as a board attachment and is
     * embedded in the comment body, matching the CRM-note pattern.
     * ------------------------------------------------------------- */

    public function commentTask(\WP_REST_Request $request)
    {
        $boardId = (int) $request['board_id'];
        $taskId  = (int) $request['task_id'];
        $text    = wp_strip_all_tags((string) $request->get_param('text'));

        try {
            $task = \FluentBoards\App\Models\Task::where('id', $taskId)
                ->where('board_id', $boardId)->first();
            if (!$task) {
                return new \WP_Error('ledgr_not_found', 'Task not found on this board', ['status' => 404]);
            }

            $body  = $text ? ('<p>' . esc_html($text) . '</p>') : '';
            $files = $request->get_file_params();
            if (isset($files['png'])) {
                $err = $this->validateUpload($files['png'], ['image/png', 'image/jpeg', 'image/webp'], self::MAX_PNG_BYTES);
                if (is_wp_error($err)) {
                    return $err;
                }
                $url = $this->attachCover($task, $boardId, $files['png']);
                if ($url) {
                    $body .= '<p><img src="' . esc_url($url) . '" alt="handwritten reply" style="max-width:100%" /></p>';
                }
            }

            if ($body === '') {
                return new \WP_Error('ledgr_bad_request', 'text or png is required', ['status' => 400]);
            }

            $comment = \FluentBoards\App\Models\Comment::create([
                'task_id'     => $taskId,
                'board_id'    => $boardId,
                'description' => $body,
                'type'        => 'comment',
                'created_by'  => get_current_user_id(),
            ]);

            do_action('fluent_boards/task_comment_added', $comment, $task);

            return rest_ensure_response([
                'comment_id' => (int) $comment->id,
                'task_id'    => $taskId,
            ]);
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_comment_failed', $e->getMessage(), ['status' => 400]);
        }
    }

    /**
     * Drop a comment that @mentions freshly-assigned users, using FluentBoards' own mention
     * pipeline (zero-width markers → processMentionAndLink → mentioned_id settings → notify), so
     * the mention links, emails, and desktop notifications all behave like a comment typed in the
     * web UI. Best-effort: a failure here never fails the assignment.
     */
    private function postAssignMention($task, $boardId, array $userIds)
    {
        try {
            if (!class_exists('\FluentBoards\App\Services\CommentService')) {
                return;
            }
            $ids = array_values(array_unique(array_map('intval', $userIds)));
            if (!$ids) {
                return;
            }

            $markers = [];
            foreach ($ids as $uid) {
                $u = get_userdata($uid);
                if ($u) {
                    // @<ZWSP>user_login<ZWNJ> — the exact marker FluentBoards parses.
                    $markers[] = '@' . "\u{200B}" . $u->user_login . "\u{200C}";
                }
            }
            if (!$markers) {
                return;
            }

            $raw     = 'Assigned ' . implode(' ', $markers);
            $service = new \FluentBoards\App\Services\CommentService();
            $linked  = $service->processMentionAndLink($raw, $ids);

            $comment = $service->create([
                'task_id'     => $task->id,
                'board_id'    => $boardId,
                'description' => $linked,
                'type'        => 'comment',
                'created_by'  => get_current_user_id(),
                'settings'    => ['raw_description' => $raw, 'mentioned_id' => $ids],
            ], $task->id, $boardId);

            if (class_exists('\FluentBoards\App\Services\NotificationService')) {
                (new \FluentBoards\App\Services\NotificationService())->mentionInComment($comment, $ids);
            }
        } catch (\Exception $e) {
            // Mention is a courtesy; assignment already succeeded.
        }
    }

    /* ---------------------------------------------------------------
     * GET /ledgr/v1/board/{board_id}/members
     * The board's people — the roster a card can be assigned to.
     * ------------------------------------------------------------- */

    public function boardMembers(\WP_REST_Request $request)
    {
        $boardId = (int) $request['board_id'];
        $out = [];
        try {
            $board = \FluentBoards\App\Models\Board::with('users')->find($boardId);
            if (!$board) {
                return new \WP_Error('ledgr_not_found', 'Board not found', ['status' => 404]);
            }
            foreach ($board->users as $u) {
                $out[] = [
                    'id'     => (int) $u->ID,
                    'name'   => $u->display_name,
                    'email'  => $u->user_email,
                    'avatar' => get_avatar_url($u->ID, ['size' => 96]),
                ];
            }
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_members_failed', $e->getMessage(), ['status' => 500]);
        }
        return rest_ensure_response(['members' => $out]);
    }

    /* ---------------------------------------------------------------
     * POST /ledgr/v1/board/{board_id}/task/{task_id}/update
     * body (all optional): assignees (csv of user ids = the full desired
     *      roster; the diff is toggled), due_at (Y-m-d or empty to clear),
     *      priority (low|medium|high|normal)
     * Each change routes through FluentBoards' own update path so email +
     * hooks fire exactly as they do in the web UI.
     * ------------------------------------------------------------- */

    public function updateTask(\WP_REST_Request $request)
    {
        $boardId = (int) $request['board_id'];
        $taskId  = (int) $request['task_id'];

        try {
            $service = new \FluentBoards\App\Services\TaskService();
            $task    = $service->findTaskOnBoard($taskId, $boardId);
            if (!$task) {
                return new \WP_Error('ledgr_not_found', 'Task not found on this board', ['status' => 404]);
            }

            // Assignees: params carry the whole desired roster; toggle the diff.
            if ($request->get_param('assignees') !== null) {
                $csv     = (string) $request->get_param('assignees');
                $desired = array_filter(array_map('intval', array_filter(explode(',', $csv), 'strlen')));
                $current = $task->assignees->pluck('ID')->map('intval')->toArray();
                $toAdd    = array_diff($desired, $current);
                $toRemove = array_diff($current, $desired);
                foreach (array_merge($toAdd, $toRemove) as $uid) {
                    $service->updateAssignee($uid, $task);
                }
                $task = \FluentBoards\App\Models\Task::find($taskId);

                // Tagging someone on a card @mentions them in the thread, so they're
                // pulled into the conversation (email + notification), not just added.
                // Default on; pass mention=0 to suppress. Only the net-new assignees.
                $mention = $request->get_param('mention');
                if (!empty($toAdd) && $mention !== '0' && $mention !== 'false') {
                    $this->postAssignMention($task, $boardId, array_values($toAdd));
                }
            }

            if ($request->get_param('due_at') !== null) {
                $due = trim((string) $request->get_param('due_at'));
                $task = $service->updateTaskProperty('due_at', $due ?: null, $task);
            }

            if ($request->get_param('priority') !== null) {
                $pri = sanitize_text_field((string) $request->get_param('priority'));
                if (in_array($pri, ['low', 'medium', 'high', 'normal'], true)) {
                    $task = $service->updateTaskProperty('priority', $pri, $task);
                }
            }

            $task = \FluentBoards\App\Models\Task::find($taskId);
            return rest_ensure_response([
                'task_id'   => (int) $task->id,
                'priority'  => $task->priority,
                'due_at'    => $task->due_at,
                'assignees' => $task->assignees->pluck('ID')->map('intval')->values()->all(),
            ]);
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_update_failed', $e->getMessage(), ['status' => 400]);
        }
    }

    /* ---------------------------------------------------------------
     * GET /ledgr/v1/card/{task_id}/strokes
     * Streams the stored .json.gz back for re-editing in ink.
     * ------------------------------------------------------------- */

    public function getStrokes(\WP_REST_Request $request)
    {
        $taskId = (int) $request['task_id'];
        $meta   = $this->getTaskMeta($taskId, self::META_STROKES);

        if (!$meta || empty($meta['file'])) {
            return new \WP_Error('ledgr_no_strokes', 'No stroke archive for this task', ['status' => 404]);
        }

        $task = \FluentBoards\App\Models\Task::find($taskId);
        if (!$task) {
            return new \WP_Error('ledgr_not_found', 'Task not found', ['status' => 404]);
        }

        $path = \FluentBoards\App\Services\Libs\FileSystem::setSubDir('board_' . $task->board_id)
            ->getDir() . DIRECTORY_SEPARATOR . $meta['file'];

        if (!file_exists($path)) {
            return new \WP_Error('ledgr_missing_file', 'Stroke archive missing on disk', ['status' => 410]);
        }

        // Stream raw gzip; the client inflates.
        header('Content-Type: application/gzip');
        header('Content-Length: ' . filesize($path));
        header('Content-Disposition: attachment; filename="strokes-' . $taskId . '.json.gz"');
        readfile($path);
        exit;
    }

    /* ---------------------------------------------------------------
     * Stage-change webhook → n8n
     * Set option 'ledgr_fb_webhook_url' (or filter) to enable.
     * ------------------------------------------------------------- */

    public function onStageUpdated($task, $oldStageId)
    {
        $url = apply_filters('ledgr_fb/webhook_url', get_option('ledgr_fb_webhook_url', ''));

        if (!$url) {
            return;
        }

        // Fire for Ledgr-sourced tasks by default; filterable to all.
        if ($task->source !== self::SOURCE && !apply_filters('ledgr_fb/webhook_all_tasks', false)) {
            return;
        }

        wp_remote_post($url, [
            'timeout'  => 3,
            'blocking' => false,
            'headers'  => ['Content-Type' => 'application/json'],
            'body'     => wp_json_encode([
                'event'          => 'task_stage_updated',
                'task_id'        => $task->id,
                'board_id'       => (int) $task->board_id,
                'old_stage_id'   => (int) $oldStageId,
                'new_stage_id'   => (int) $task->stage_id,
                'title'          => $task->title,
                'crm_contact_id' => $task->crm_contact_id,
                'source'         => $task->source,
                'source_id'      => $task->source_id,
            ]),
        ]);
    }

    /* ---------------------------------------------------------------
     * POST /ledgr/v1/crm/note — handwritten contact note
     * multipart: png (optional), note_uuid, text (OCR),
     *            email | contact_id | name_hint
     * Matches or creates the contact, attaches a note with the
     * handwriting image inline, applies #tags parsed from text.
     * ------------------------------------------------------------- */

    public function createCrmNote(\WP_REST_Request $request)
    {
        if (!function_exists('FluentCrmApi')) {
            return new \WP_Error('ledgr_no_crm', 'FluentCRM is not active on this site', ['status' => 501]);
        }

        $noteUuid  = sanitize_text_field($request->get_param('note_uuid'));
        $text      = wp_strip_all_tags((string) $request->get_param('text'));
        $email     = sanitize_email((string) $request->get_param('email'));
        $contactId = (int) $request->get_param('contact_id');
        $nameHint  = sanitize_text_field((string) $request->get_param('name_hint'));

        if (!$noteUuid) {
            return new \WP_Error('ledgr_bad_request', 'note_uuid is required', ['status' => 400]);
        }

        // Pull an email out of the OCR text if none was supplied.
        if (!$email && $text && preg_match('/[\w.+\-]+@[\w\-]+\.[\w.\-]+/', $text, $m)) {
            $email = sanitize_email($m[0]);
        }

        $contactApi = FluentCrmApi('contacts');
        $contact    = null;
        $created    = false;

        if ($contactId) {
            $contact = $contactApi->getContact($contactId);
        } elseif ($email) {
            $contact = $contactApi->getContact($email);
            if (!$contact) {
                $names   = $this->splitName($nameHint ?: strtok($text, "\n"));
                $contact = $contactApi->createOrUpdate([
                    'email'      => $email,
                    'first_name' => $names[0],
                    'last_name'  => $names[1],
                    'status'     => 'subscribed',
                ]);
                $created = true;
            }
        }

        if (!$contact) {
            return new \WP_Error(
                'ledgr_no_match',
                'No contact match — supply contact_id or an email (in params or OCR text)',
                ['status' => 422]
            );
        }

        // #tags shorthand → FluentCRM tags (created if missing).
        $appliedTags = [];
        if ($text && preg_match_all('/#([a-z0-9\-_]+)/i', $text, $tagMatches)) {
            $appliedTags = $this->applyTags($contact, $tagMatches[1]);
        }

        // Handwriting image → WP media library, embedded in the note.
        $imageHtml = '';
        $imageUrl  = null;
        $files     = $request->get_file_params();

        if (isset($files['png'])) {
            $err = $this->validateUpload($files['png'], ['image/png', 'image/jpeg', 'image/webp'], self::MAX_PNG_BYTES);
            if (!is_wp_error($err)) {
                $imageUrl = $this->sideloadToMedia($files['png']);
                if ($imageUrl) {
                    $imageHtml = '<p><img src="' . esc_url($imageUrl) . '" alt="Handwritten note" style="max-width:100%" /></p>';
                }
            }
        }

        $noteBody = $imageHtml . ($text ? '<p>' . nl2br(esc_html($text)) . '</p>' : '');

        $note = null;
        if (class_exists('\FluentCrm\App\Models\SubscriberNote')) {
            $note = \FluentCrm\App\Models\SubscriberNote::create([
                'subscriber_id' => $contact->id,
                'type'          => 'note',
                'title'         => 'Handwritten note (Ledgr)',
                'description'   => $noteBody,
                'created_by'    => get_current_user_id(),
            ]);
        }

        /**
         * Hook point for sequence adds, automations, n8n side effects.
         * FluentCampaign Pro sequence attach can listen here.
         */
        do_action('ledgr_fb/crm_note_created', $contact, $note, [
            'note_uuid' => $noteUuid,
            'tags'      => $appliedTags,
            'created'   => $created,
            'image_url' => $imageUrl,
        ]);

        return rest_ensure_response([
            'contact_id'      => (int) $contact->id,
            'contact_email'   => $contact->email,
            'contact_name'    => trim($contact->first_name . ' ' . $contact->last_name),
            'contact_created' => $created,
            'note_id'         => $note ? $note->id : null,
            'tags_applied'    => $appliedTags,
            'image_url'       => $imageUrl,
        ]);
    }

    /* ---------------------------------------------------------------
     * GET /ledgr/v1/crm/contact/{contact_id}/compact
     * One-screen e-ink contact card: identity, tags, latest note,
     * open FluentBoards cards linked via crm_contact_id.
     * ------------------------------------------------------------- */

    public function compactContact(\WP_REST_Request $request)
    {
        if (!function_exists('FluentCrmApi')) {
            return new \WP_Error('ledgr_no_crm', 'FluentCRM is not active on this site', ['status' => 501]);
        }

        $contactId = (int) $request['contact_id'];
        $contact   = FluentCrmApi('contacts')->getContact($contactId);

        if (!$contact) {
            return new \WP_Error('ledgr_not_found', 'Contact not found', ['status' => 404]);
        }

        $tags = [];
        foreach ($contact->tags as $tag) {
            $tags[] = $tag->title;
        }

        $lastNote = null;
        if (class_exists('\FluentCrm\App\Models\SubscriberNote')) {
            $noteModel = \FluentCrm\App\Models\SubscriberNote::where('subscriber_id', $contactId)
                ->orderBy('id', 'desc')
                ->first();
            if ($noteModel) {
                $lastNote = [
                    'title'      => $noteModel->title,
                    'excerpt'    => wp_trim_words(wp_strip_all_tags($noteModel->description), 30),
                    'created_at' => (string) $noteModel->created_at,
                ];
            }
        }

        $cards = [];
        $tasks = \FluentBoards\App\Models\Task::where('crm_contact_id', $contactId)
            ->whereNull('archived_at')
            ->where('status', '!=', 'closed')
            ->orderBy('updated_at', 'desc')
            ->limit(20)
            ->get(['id', 'title', 'board_id', 'stage_id', 'due_at', 'priority', 'settings', 'source']);

        foreach ($tasks as $task) {
            $settings = $task->settings;
            $cards[]  = [
                'id'        => $task->id,
                'title'     => $task->title,
                'board_id'  => (int) $task->board_id,
                'stage_id'  => (int) $task->stage_id,
                'due_at'    => $task->due_at,
                'priority'  => $task->priority,
                'cover_url' => isset($settings['cover']['backgroundImage']) ? $settings['cover']['backgroundImage'] : null,
                'is_ledgr'  => $task->source === self::SOURCE,
            ];
        }

        return rest_ensure_response([
            'id'            => (int) $contact->id,
            'name'          => trim($contact->first_name . ' ' . $contact->last_name),
            'email'         => $contact->email,
            'phone'         => $contact->phone,
            'status'        => $contact->status,
            'tags'          => $tags,
            'last_activity' => (string) $contact->last_activity,
            'last_note'     => $lastNote,
            'open_cards'    => $cards,
        ]);
    }

    /* ---------------------------------------------------------------
     * POST /ledgr/v1/community/ingest — push RSS items into a space
     * json: { space_id, user_id?, items: [{guid,title,url,excerpt,published_at}] }
     * Miniflux → n8n → here. Dedupes by guid. Cron pull also lands here.
     * ------------------------------------------------------------- */

    public function ingestFeedItems(\WP_REST_Request $request)
    {
        if (!class_exists('\FluentCommunity\App\Models\Feed')) {
            return new \WP_Error('ledgr_no_community', 'FluentCommunity is not active', ['status' => 501]);
        }

        $body    = $request->get_json_params();
        $spaceId = isset($body['space_id']) ? (int) $body['space_id'] : 0;
        $userId  = isset($body['user_id']) ? (int) $body['user_id'] : get_current_user_id();
        $items   = isset($body['items']) && is_array($body['items']) ? $body['items'] : [];

        if (!$spaceId || !$items) {
            return new \WP_Error('ledgr_bad_request', 'space_id and items are required', ['status' => 400]);
        }

        $created = [];
        $skipped = 0;

        foreach ($items as $item) {
            $feedId = $this->createSpacePostFromItem($spaceId, $userId, $item);
            if ($feedId) {
                $created[] = $feedId;
            } else {
                $skipped++;
            }
        }

        return rest_ensure_response([
            'created'  => $created,
            'skipped'  => $skipped,
            'space_id' => $spaceId,
        ]);
    }

    /**
     * Hourly cron pull. Option ledgr_fb_rss_map:
     * [ { "feed_url": "...", "space_id": 5, "user_id": 1, "max": 10 }, ... ]
     */
    public function pullRssFeeds()
    {
        if (!class_exists('\FluentCommunity\App\Models\Feed')) {
            return;
        }

        $map = get_option('ledgr_fb_rss_map', []);
        if (!is_array($map)) {
            return;
        }

        include_once ABSPATH . WPINC . '/feed.php';

        foreach ($map as $entry) {
            if (empty($entry['feed_url']) || empty($entry['space_id'])) {
                continue;
            }

            $rss = fetch_feed(esc_url_raw($entry['feed_url']));
            if (is_wp_error($rss)) {
                continue;
            }

            $max    = isset($entry['max']) ? (int) $entry['max'] : 10;
            $userId = isset($entry['user_id']) ? (int) $entry['user_id'] : 0;

            foreach ($rss->get_items(0, $rss->get_item_quantity($max)) as $item) {
                $this->createSpacePostFromItem((int) $entry['space_id'], $userId, [
                    'guid'         => $item->get_id() ?: $item->get_permalink(),
                    'title'        => $item->get_title(),
                    'url'          => $item->get_permalink(),
                    'excerpt'      => wp_trim_words(wp_strip_all_tags((string) $item->get_description()), 60),
                    'published_at' => $item->get_date('Y-m-d H:i:s'),
                ]);
            }
        }
    }

    /* ---------------------------------------------------------------
     * POST /ledgr/v1/community/comment — handwritten annotation
     * multipart: png (optional), note_uuid, feed_id, text (OCR)
     * Idempotent on note_uuid.
     * ------------------------------------------------------------- */

    public function createCommunityComment(\WP_REST_Request $request)
    {
        if (!class_exists('\FluentCommunity\App\Models\Comment')) {
            return new \WP_Error('ledgr_no_community', 'FluentCommunity is not active', ['status' => 501]);
        }

        $noteUuid = sanitize_text_field($request->get_param('note_uuid'));
        $feedId   = (int) $request->get_param('feed_id');
        $text     = wp_strip_all_tags((string) $request->get_param('text'));

        if (!$noteUuid || !$feedId) {
            return new \WP_Error('ledgr_bad_request', 'note_uuid and feed_id are required', ['status' => 400]);
        }

        // Idempotency.
        $seen = get_option('ledgr_fb_note_comments', []);
        if (isset($seen[$noteUuid])) {
            return rest_ensure_response(['comment_id' => $seen[$noteUuid], 'existing' => true]);
        }

        $feed = \FluentCommunity\App\Models\Feed::find($feedId);
        if (!$feed) {
            return new \WP_Error('ledgr_not_found', 'Post not found', ['status' => 404]);
        }

        if ($feed->space_id) {
            $membership = $this->assertSpaceMembership((int) $feed->space_id);
            if (is_wp_error($membership)) {
                return $membership;
            }
        }

        $imageHtml = '';
        $files     = $request->get_file_params();
        if (isset($files['png'])) {
            $err = $this->validateUpload($files['png'], ['image/png', 'image/jpeg', 'image/webp'], self::MAX_PNG_BYTES);
            if (!is_wp_error($err)) {
                $url = $this->sideloadToMedia($files['png']);
                if ($url) {
                    $imageHtml = '<p><img src="' . esc_url($url) . '" alt="Handwritten annotation" style="max-width:100%" /></p>';
                }
            }
        }

        $message = trim($text);
        $html    = $imageHtml . ($message ? '<p>' . nl2br(esc_html($message)) . '</p>' : '');

        try {
            $comment = \FluentCommunity\App\Models\Comment::create([
                'post_id'          => $feed->id,
                'user_id'          => get_current_user_id(),
                'message'          => $message ?: '[handwritten annotation]',
                'message_rendered' => $html,
                'type'             => 'comment',
                'status'           => 'published',
            ]);

            \FluentCommunity\App\Models\Feed::where('id', $feed->id)->increment('comments_count');

            $seen[$noteUuid] = $comment->id;
            if (count($seen) > 1000) {
                $seen = array_slice($seen, -800, null, true);
            }
            update_option('ledgr_fb_note_comments', $seen, false);

            do_action('ledgr_fb/community_comment_created', $comment, $feed, $noteUuid);

            return rest_ensure_response([
                'comment_id' => $comment->id,
                'feed_id'    => $feed->id,
                'existing'   => false,
            ]);
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_comment_failed', $e->getMessage(), ['status' => 400]);
        }
    }

    /* ---------------------------------------------------------------
     * GET /ledgr/v1/correspondence?since=Y-m-d H:i:s&limit=50
     * Unified reply inbox for the e-ink correspondence section:
     *   - FluentCommunity comments by others on posts you authored or
     *     annotated
     *   - FluentBoards comments on your Ledgr-sourced cards
     * ------------------------------------------------------------- */

    public function correspondence(\WP_REST_Request $request)
    {
        $uid   = get_current_user_id();
        $since = sanitize_text_field($request->get_param('since') ?: '');
        $limit = min(100, max(1, (int) ($request->get_param('limit') ?: 50)));

        $out = $this->gatherCorrespondence($uid, $since, $limit);

        return rest_ensure_response([
            'items' => $out,
            'rev'   => md5(wp_json_encode(array_column($out, 'id'))),
        ]);
    }

    /**
     * The replies-to-you set: FluentCommunity comments on your posts + FluentBoards comments on
     * your Ledgr cards, newest first, each with a stable id (fcom-/fbs-). Shared by the inbox
     * feed and the to-board sync so both see exactly the same conversation.
     */
    private function gatherCorrespondence($uid, $since, $limit)
    {
        $out = [];

        // --- FluentCommunity replies ---
        if (class_exists('\FluentCommunity\App\Models\Comment')) {
            try {
                $myPostIds = \FluentCommunity\App\Models\Comment::where('user_id', $uid)
                    ->pluck('post_id')->all();
                $authored = \FluentCommunity\App\Models\Feed::where('user_id', $uid)
                    ->pluck('id')->all();
                $postIds = array_unique(array_merge($myPostIds, $authored));

                if ($postIds) {
                    $query = \FluentCommunity\App\Models\Comment::whereIn('post_id', $postIds)
                        ->where('user_id', '!=', $uid)
                        ->orderBy('id', 'desc')
                        ->limit($limit);

                    if ($since) {
                        $query->where('created_at', '>', $since);
                    }

                    foreach ($query->get() as $c) {
                        $author = get_user_by('id', $c->user_id);
                        $feed   = \FluentCommunity\App\Models\Feed::find($c->post_id);
                        $out[]  = [
                            'source'     => 'community',
                            'thread_url' => apply_filters('ledgr_fb/thread_url', home_url('/portal/post/' . $c->post_id), 'community', $c->post_id),
                            'id'         => 'fcom-' . $c->id,
                            'thread'     => $feed ? wp_trim_words($feed->title ?: wp_strip_all_tags($feed->message), 10) : null,
                            'thread_id'  => (int) $c->post_id,
                            'author'     => $author ? $author->display_name : ('User ' . $c->user_id),
                            'excerpt'    => wp_trim_words(wp_strip_all_tags($c->message_rendered ?: $c->message), 40),
                            'created_at' => (string) $c->created_at,
                        ];
                    }
                }
            } catch (\Exception $e) {
                // Community schema drift — skip section rather than fail the inbox.
            }
        }

        // --- FluentBoards comments on Ledgr cards ---
        try {
            $ledgrTaskIds = \FluentBoards\App\Models\Task::where('source', self::SOURCE)
                ->pluck('id')->all();

            if ($ledgrTaskIds) {
                $query = \FluentBoards\App\Models\Comment::whereIn('task_id', $ledgrTaskIds)
                    ->where('created_by', '!=', $uid)
                    ->orderBy('id', 'desc')
                    ->limit($limit);

                if ($since) {
                    $query->where('created_at', '>', $since);
                }

                foreach ($query->get() as $c) {
                    $task  = \FluentBoards\App\Models\Task::find($c->task_id);
                    $out[] = [
                        'source'     => 'boards',
                        'thread_url' => apply_filters('ledgr_fb/thread_url', admin_url('admin.php?page=fluent-boards'), 'boards', $c->task_id),
                        'id'         => 'fbs-' . $c->id,
                        'thread'     => $task ? $task->title : null,
                        'thread_id'  => (int) $c->task_id,
                        'author'     => $c->author_name ?: (($u = get_user_by('id', $c->created_by)) ? $u->display_name : 'Unknown'),
                        'excerpt'    => wp_trim_words(wp_strip_all_tags($c->description), 40),
                        'created_at' => (string) $c->created_at,
                    ];
                }
            }
        } catch (\Exception $e) {
            // Same defensive posture.
        }

        usort($out, function ($a, $b) {
            return strcmp($b['created_at'], $a['created_at']);
        });

        return array_slice($out, 0, $limit);
    }

    /* ---------------------------------------------------------------
     * POST /ledgr/v1/correspondence/to-board
     * body: board_id (required), stage_id (optional; default first
     *       stage), limit, since
     * Lands each reply on your shared items as a card on the board, in
     * the first column — "waiting for your next move." Idempotent on the
     * reply id (source=ledgr-inbox, source_id=fcom-/fbs-id), so re-syncing
     * only ever adds the new ones. The thread text + a link ride in the
     * card so you can move it, assign it, or reply straight from the board.
     * ------------------------------------------------------------- */

    public function correspondenceToBoard(\WP_REST_Request $request)
    {
        $boardId = (int) $request->get_param('board_id');
        $stageId = (int) $request->get_param('stage_id');
        $since   = sanitize_text_field($request->get_param('since') ?: '');
        $limit   = min(100, max(1, (int) ($request->get_param('limit') ?: 50)));
        $uid     = get_current_user_id();

        if (!$boardId) {
            return new \WP_Error('ledgr_bad_request', 'board_id is required', ['status' => 400]);
        }

        // Default to the board's first column.
        if (!$stageId) {
            $firstStage = \FluentBoards\App\Models\Stage::where('board_id', $boardId)
                ->whereNull('archived_at')->orderBy('position', 'asc')->first();
            if (!$firstStage) {
                return new \WP_Error('ledgr_no_stage', 'Board has no columns', ['status' => 400]);
            }
            $stageId = (int) $firstStage->id;
        }

        $replies = $this->gatherCorrespondence($uid, $since, $limit);

        $created = 0;
        $skipped = 0;
        try {
            $taskService = new \FluentBoards\App\Services\TaskService();
            foreach ($replies as $r) {
                $sourceId = $r['id'];   // fcom-<id> / fbs-<id>

                $exists = \FluentBoards\App\Models\Task::where('source', 'ledgr-inbox')
                    ->where('source_id', $sourceId)->first();
                if ($exists) { $skipped++; continue; }

                $thread = $r['thread'] ?: ($r['source'] === 'community' ? 'a post' : 'a card');
                $title  = '↩ ' . $r['author'] . ': ' . wp_trim_words($r['excerpt'], 12);

                $task = $taskService->createTask([
                    'title'     => $title,
                    'board_id'  => $boardId,
                    'stage_id'  => $stageId,
                    'source'    => 'ledgr-inbox',
                    'source_id' => $sourceId,
                ], $boardId);
                $task = \FluentBoards\App\Models\Task::find($task->id);

                $link = !empty($r['thread_url'])
                    ? '<p><a href="' . esc_url($r['thread_url']) . '">Open the ' . esc_html($r['source']) . ' thread ↗</a></p>'
                    : '';
                $task->description = '<p><strong>' . esc_html($r['author']) . '</strong> on <em>'
                    . esc_html($thread) . '</em>:</p><p>' . esc_html($r['excerpt']) . '</p>' . $link;
                $task->save();

                $created++;
            }
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_inbox_failed', $e->getMessage(), ['status' => 500]);
        }

        return rest_ensure_response([
            'board_id' => $boardId,
            'stage_id' => $stageId,
            'created'  => $created,
            'skipped'  => $skipped,
        ]);
    }

    /* ---------------------------------------------------------------
     * GET /ledgr/v1/person/compact?contact_id=|user_id=|email=
     * The full person surface: CRM identity (photo, tags, status) +
     * community XProfile (avatar, bio, points) + prior correspondence
     * between you and them + their open cards. One screen of ink-height
     * context before you write to someone.
     * ------------------------------------------------------------- */

    public function compactPerson(\WP_REST_Request $request)
    {
        $contactId = (int) $request->get_param('contact_id');
        $userId    = (int) $request->get_param('user_id');
        $email     = sanitize_email((string) $request->get_param('email'));

        $person = [
            'name' => null, 'email' => null, 'avatar' => null, 'bio' => null,
            'crm' => null, 'community' => null, 'open_cards' => [], 'history' => [],
        ];

        // --- CRM identity ---
        $contact = null;
        if (function_exists('FluentCrmApi')) {
            $api = FluentCrmApi('contacts');
            if ($contactId) {
                $contact = $api->getContact($contactId);
            } elseif ($email) {
                $contact = $api->getContact($email);
            } elseif ($userId && ($wpUser = get_user_by('id', $userId))) {
                $contact = $api->getContact($wpUser->user_email);
            }
        }

        if ($contact) {
            $tags = [];
            foreach ($contact->tags as $tag) {
                $tags[] = $tag->title;
            }
            $person['name']  = trim($contact->first_name . ' ' . $contact->last_name);
            $person['email'] = $contact->email;
            $person['avatar'] = !empty($contact->avatar) ? $contact->avatar : get_avatar_url($contact->email);
            $person['crm']   = [
                'id'            => (int) $contact->id,
                'status'        => $contact->status,
                'phone'         => $contact->phone,
                'tags'          => $tags,
                'last_activity' => (string) $contact->last_activity,
            ];
            if (!$userId && !empty($contact->user_id)) {
                $userId = (int) $contact->user_id;
            }
            if (!$userId && ($wpUser = get_user_by('email', $contact->email))) {
                $userId = (int) $wpUser->ID;
            }
        }

        // --- Community identity (XProfile) ---
        if ($userId && class_exists('\FluentCommunity\App\Models\XProfile')) {
            try {
                $xp = \FluentCommunity\App\Models\XProfile::where('user_id', $userId)->first();
                if ($xp) {
                    $person['name']   = $person['name'] ?: $xp->display_name;
                    $person['avatar'] = !empty($xp->avatar) ? $xp->avatar : $person['avatar'];
                    $person['bio']    = !empty($xp->short_description) ? $xp->short_description : null;
                    $person['community'] = [
                        'user_id'      => $userId,
                        'display_name' => $xp->display_name,
                        'points'       => isset($xp->total_points) ? (int) $xp->total_points : null,
                        'status'       => $xp->status,
                        'posts'        => class_exists('\FluentCommunity\App\Models\Feed')
                            ? \FluentCommunity\App\Models\Feed::where('user_id', $userId)->where('status', 'published')->count()
                            : null,
                    ];
                }
            } catch (\Exception $e) {
                // XProfile schema drift — identity section stays CRM-only.
            }
        }

        if (!$person['avatar'] && $userId) {
            $person['avatar'] = get_avatar_url($userId);
        }

        // --- Open cards ---
        if ($contact) {
            $tasks = \FluentBoards\App\Models\Task::where('crm_contact_id', $contact->id)
                ->whereNull('archived_at')->where('status', '!=', 'closed')
                ->orderBy('updated_at', 'desc')->limit(10)
                ->get(['id', 'title', 'board_id', 'stage_id', 'due_at', 'settings']);
            foreach ($tasks as $task) {
                $settings = $task->settings;
                $person['open_cards'][] = [
                    'id' => $task->id, 'title' => $task->title,
                    'board_id' => (int) $task->board_id, 'due_at' => $task->due_at,
                    'cover_url' => isset($settings['cover']['backgroundImage']) ? $settings['cover']['backgroundImage'] : null,
                ];
            }
        }

        // --- Prior correspondence between viewer and this person ---
        $me = get_current_user_id();
        if ($userId && class_exists('\FluentCommunity\App\Models\Comment')) {
            try {
                $theirThreads = \FluentCommunity\App\Models\Comment::where('user_id', $userId)->pluck('post_id')->all();
                $myThreads    = \FluentCommunity\App\Models\Comment::where('user_id', $me)->pluck('post_id')->all();
                $shared       = array_slice(array_intersect($theirThreads, $myThreads), 0, 50);

                if ($shared) {
                    $comments = \FluentCommunity\App\Models\Comment::whereIn('post_id', $shared)
                        ->whereIn('user_id', [$me, $userId])
                        ->orderBy('id', 'desc')->limit(20)->get();
                    foreach ($comments as $c) {
                        $person['history'][] = [
                            'thread_id'  => (int) $c->post_id,
                            'mine'       => (int) $c->user_id === $me,
                            'excerpt'    => wp_trim_words(wp_strip_all_tags($c->message_rendered ?: $c->message), 30),
                            'created_at' => (string) $c->created_at,
                        ];
                    }
                }
            } catch (\Exception $e) {
                // History stays empty on drift.
            }
        }

        if (!$person['name'] && !$person['community']) {
            return new \WP_Error('ledgr_not_found', 'No person found for the given identifier', ['status' => 404]);
        }

        return rest_ensure_response($person);
    }

    /* ---------------------------------------------------------------
     * POST /ledgr/v1/community/gram — handwritten/voice post
     * multipart: png (the gram), audio (optional voice note),
     *            space_id, note_uuid, text (OCR), title (optional)
     * The stroke render IS the post; OCR text rides along for search.
     * ------------------------------------------------------------- */

    public function createGram(\WP_REST_Request $request)
    {
        if (!class_exists('\FluentCommunity\App\Models\Feed')) {
            return new \WP_Error('ledgr_no_community', 'FluentCommunity is not active', ['status' => 501]);
        }

        $noteUuid = sanitize_text_field($request->get_param('note_uuid'));
        $spaceId  = (int) $request->get_param('space_id');
        $text     = wp_strip_all_tags((string) $request->get_param('text'));
        $title    = sanitize_text_field((string) $request->get_param('title'));

        if (!$noteUuid || !$spaceId) {
            return new \WP_Error('ledgr_bad_request', 'note_uuid and space_id are required', ['status' => 400]);
        }

        $membership = $this->assertSpaceMembership($spaceId);
        if (is_wp_error($membership)) {
            return $membership;
        }

        $seen = get_option('ledgr_fb_note_grams', []);
        if (isset($seen[$noteUuid])) {
            return rest_ensure_response(['feed_id' => $seen[$noteUuid], 'existing' => true]);
        }

        $files     = $request->get_file_params();
        $mediaHtml = '';
        $imageUrl  = null;
        $audioUrl  = null;

        if (isset($files['png'])) {
            $err = $this->validateUpload($files['png'], ['image/png', 'image/jpeg', 'image/webp'], self::MAX_PNG_BYTES);
            if (is_wp_error($err)) {
                return $err;
            }
            $imageUrl = $this->sideloadToMedia($files['png']);
            if ($imageUrl) {
                $mediaHtml .= '<p><img src="' . esc_url($imageUrl) . '" alt="' . esc_attr($title ?: 'Handwritten post') . '" style="max-width:100%" /></p>';
            }
        }

        if (isset($files['audio'])) {
            $err = $this->validateUpload($files['audio'], ['audio/mpeg', 'audio/mp4', 'audio/x-m4a', 'audio/ogg', 'audio/wav', 'audio/webm'], self::MAX_PNG_BYTES);
            if (!is_wp_error($err)) {
                $audioUrl = $this->sideloadToMedia($files['audio']);
                if ($audioUrl) {
                    $mediaHtml .= '<p><audio controls src="' . esc_url($audioUrl) . '"></audio></p>';
                }
            }
        }

        // Video: short clips upload directly; real video passes video_url
        // (Bunny Stream play/iframe URL) and embeds without touching WP media.
        $videoUrl = esc_url_raw((string) $request->get_param('video_url'));

        if (!$videoUrl && isset($files['video'])) {
            $err = $this->validateUpload($files['video'], ['video/mp4', 'video/webm', 'video/quicktime'], self::MAX_VIDEO_BYTES);
            if (!is_wp_error($err)) {
                $videoUrl = $this->sideloadToMedia($files['video']);
            }
        }

        if ($videoUrl) {
            if (strpos($videoUrl, 'iframe') !== false || strpos($videoUrl, 'mediadelivery.net/embed') !== false) {
                $mediaHtml .= '<p><iframe src="' . esc_url($videoUrl) . '" loading="lazy" style="width:100%;aspect-ratio:16/9;border:0" allow="autoplay;fullscreen" allowfullscreen></iframe></p>';
            } else {
                $mediaHtml .= '<p><video controls playsinline style="max-width:100%" src="' . esc_url($videoUrl) . '"></video></p>';
            }
        }

        if (!$mediaHtml && !$text) {
            return new \WP_Error('ledgr_empty', 'A gram needs ink, voice, or text', ['status' => 400]);
        }

        $html = $mediaHtml . ($text ? '<p>' . nl2br(esc_html($text)) . '</p>' : '');

        try {
            $feed = \FluentCommunity\App\Models\Feed::create([
                'user_id'          => get_current_user_id(),
                'space_id'         => $spaceId,
                'title'            => $title ?: wp_trim_words($text, 8, '…'),
                'message'          => $text ?: ($title ?: '[handwritten post]'),
                'message_rendered' => $html,
                'type'             => 'text',
                'status'           => 'published',
            ]);

            $seen[$noteUuid] = $feed->id;
            if (count($seen) > 1000) {
                $seen = array_slice($seen, -800, null, true);
            }
            update_option('ledgr_fb_note_grams', $seen, false);

            do_action('ledgr_fb/gram_created', $feed, [
                'note_uuid' => $noteUuid,
                'image_url' => $imageUrl,
                'audio_url' => $audioUrl,
                'video_url' => $videoUrl ?: null,
            ]);

            return rest_ensure_response([
                'feed_id'   => $feed->id,
                'space_id'  => $spaceId,
                'image_url' => $imageUrl,
                'audio_url' => $audioUrl,
                'video_url' => $videoUrl ?: null,
                'existing'  => false,
            ]);
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_gram_failed', $e->getMessage(), ['status' => 400]);
        }
    }

    /* ---------------------------------------------------------------
     * POST /ledgr/v1/essay — a handwritten essay leaves the Write page.
     * multipart: png (the page render), title, tags (comma list), text
     *            (OCR/typed body, optional), dest = draft | email,
     *            post_type (draft dest; default "post"), to (email dest)
     * draft → wp_insert_post (draft, image + text as content, tags)
     * email → wp_mail to [to] with the same content inline.
     * Idempotency is NOT applied — each send is a deliberate act.
     * ------------------------------------------------------------- */

    public function createEssay(\WP_REST_Request $request)
    {
        $title = sanitize_text_field((string) $request->get_param('title'));
        $dest  = sanitize_text_field((string) $request->get_param('dest'));
        $text  = wp_strip_all_tags((string) $request->get_param('text'));
        $tags  = array_filter(array_map('trim', explode(',', (string) $request->get_param('tags'))));

        if (!$title || !in_array($dest, ['draft', 'email'], true)) {
            return new \WP_Error('ledgr_bad_request', 'title and dest (draft|email) are required', ['status' => 400]);
        }

        $imageHtml = '';
        $imageUrl  = null;
        $files     = $request->get_file_params();
        if (isset($files['png'])) {
            $err = $this->validateUpload($files['png'], ['image/png', 'image/jpeg', 'image/webp'], self::MAX_PNG_BYTES);
            if (!is_wp_error($err)) {
                $imageUrl = $this->sideloadToMedia($files['png']);
                if ($imageUrl) {
                    $imageHtml = '<p><img src="' . esc_url($imageUrl) . '" alt="' . esc_attr($title) . '" style="max-width:100%" /></p>';
                }
            }
        }
        $content = $imageHtml . ($text ? '<p>' . nl2br(esc_html($text)) . '</p>' : '');
        if (!$content) {
            return new \WP_Error('ledgr_empty', 'An essay needs ink or text', ['status' => 400]);
        }

        if ($dest === 'draft') {
            $postType = sanitize_key((string) ($request->get_param('post_type') ?: 'post'));
            if (!post_type_exists($postType)) {
                $postType = 'post';
            }
            $postId = wp_insert_post([
                'post_title'   => $title,
                'post_content' => $content,
                'post_status'  => 'draft',
                'post_type'    => $postType,
                'post_author'  => get_current_user_id(),
            ], true);
            if (is_wp_error($postId)) {
                return $postId;
            }
            if ($tags && $postType === 'post') {
                wp_set_post_tags($postId, $tags, false);
            }
            do_action('ledgr_fb/essay_drafted', $postId, $imageUrl);
            return rest_ensure_response([
                'post_id'  => $postId,
                'edit_url' => get_edit_post_link($postId, 'raw'),
                'image_url' => $imageUrl,
            ]);
        }

        // email
        $to = sanitize_email((string) $request->get_param('to'));
        if (!$to) {
            return new \WP_Error('ledgr_bad_request', 'dest=email needs a valid "to" address', ['status' => 400]);
        }
        $headers = ['Content-Type: text/html; charset=UTF-8'];
        $sent = wp_mail($to, $title, $content, $headers);
        do_action('ledgr_fb/essay_emailed', $to, $title, $imageUrl, $sent);
        return rest_ensure_response(['sent' => (bool) $sent, 'to' => $to, 'image_url' => $imageUrl]);
    }

    /* ---------------------------------------------------------------
     * GET /ledgr/v1/community/spaces — picker list for the client
     * ------------------------------------------------------------- */

    public function listSpaces()
    {
        if (!class_exists('\FluentCommunity\App\Models\Space')) {
            return new \WP_Error('ledgr_no_community', 'FluentCommunity is not active', ['status' => 501]);
        }

        $out = [];
        try {
            // Members see only public spaces + spaces they belong to; admins see everything.
            $isAdmin = current_user_can('manage_options');
            $memberOf = [];
            if (!$isAdmin) {
                try {
                    $memberOf = \FluentCommunity\App\Models\SpaceUserPivot::where('user_id', get_current_user_id())
                        ->pluck('space_id')->all();
                } catch (\Exception $e) {
                    $memberOf = [];
                }
            }
            foreach (\FluentCommunity\App\Models\Space::orderBy('title')->get() as $space) {
                if (!$isAdmin && $space->privacy !== 'public' && !in_array($space->id, $memberOf)) {
                    continue;
                }
                $out[] = [
                    'id'      => $space->id,
                    'title'   => $space->title,
                    'slug'    => $space->slug,
                    'privacy' => $space->privacy,
                ];
            }
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_spaces_failed', $e->getMessage(), ['status' => 500]);
        }

        return rest_ensure_response(['spaces' => $out]);
    }

    /* ---------------------------------------------------------------
     * GET /ledgr/v1/community/feed?space=<id>&limit=N
     * A space's posts (the cute cards shared in), newest first, so the
     * Ledger's Correspondence view can show them as repliable cards.
     * ------------------------------------------------------------- */
    public function spaceFeed(\WP_REST_Request $request)
    {
        if (!class_exists('\FluentCommunity\App\Models\Feed')) {
            return new \WP_Error('ledgr_no_community', 'FluentCommunity is not active', ['status' => 501]);
        }
        $spaceId = (int) $request->get_param('space');
        $limit   = min(100, max(1, (int) ($request->get_param('limit') ?: 50)));
        if (!$spaceId) {
            return new \WP_Error('ledgr_bad_request', 'space is required', ['status' => 400]);
        }
        // Members see a space only if it's public or they belong to it; admins see all.
        if (!current_user_can('manage_options')) {
            try {
                $memberOf = \FluentCommunity\App\Models\SpaceUserPivot::where('user_id', get_current_user_id())
                    ->pluck('space_id')->all();
                $space = \FluentCommunity\App\Models\Space::find($spaceId);
                if ($space && $space->privacy !== 'public' && !in_array($spaceId, $memberOf)) {
                    return new \WP_Error('ledgr_forbidden', 'not a member of this space', ['status' => 403]);
                }
            } catch (\Exception $e) { /* fall through — read-only */ }
        }
        $out = [];
        try {
            $feeds = \FluentCommunity\App\Models\Feed::where('space_id', $spaceId)
                ->orderBy('id', 'desc')->limit($limit)->get();
            foreach ($feeds as $f) {
                $author = get_user_by('id', $f->user_id);
                $body   = (string) ($f->message_rendered ?: $f->message);
                $out[]  = [
                    'id'             => (int) $f->id,
                    'title'          => $f->title ?: null,
                    'excerpt'        => wp_trim_words(wp_strip_all_tags($body), 60),
                    'html'           => $body,
                    'author'         => $author ? $author->display_name : ('User ' . $f->user_id),
                    'created_at'     => (string) $f->created_at,
                    'comments_count' => (int) ($f->comments_count ?? 0),
                    'url'            => (method_exists($f, 'getPermalink') ? $f->getPermalink()
                                             : apply_filters('ledgr_fb/thread_url', home_url('/portal/post/' . $f->id), 'community', $f->id)),
                ];
            }
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_feed_failed', $e->getMessage(), ['status' => 500]);
        }
        return rest_ensure_response(['space_id' => $spaceId, 'items' => $out]);
    }

    /* ---------------------------------------------------------------
     * GET /ledgr/v1/community/courses
     * GET /ledgr/v1/community/course/{id}/lessons
     * Read-only course consumption for e-ink; lesson HTML renders as
     * paginated text. Completion tracking is a later pass.
     * ------------------------------------------------------------- */

    public function listCourses()
    {
        if (!class_exists('\FluentCommunity\Modules\Course\Model\Course')) {
            return new \WP_Error('ledgr_no_courses', 'FluentCommunity course module is not available', ['status' => 501]);
        }

        $out = [];
        try {
            $courses = \FluentCommunity\Modules\Course\Model\Course::where('status', 'published')
                ->orderBy('title')->get();
            foreach ($courses as $course) {
                $out[] = [
                    'id'      => $course->id,
                    'title'   => $course->title,
                    'slug'    => $course->slug,
                    'privacy' => $course->privacy,
                ];
            }
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_courses_failed', $e->getMessage(), ['status' => 500]);
        }

        return rest_ensure_response(['courses' => $out]);
    }

    public function courseLessons(\WP_REST_Request $request)
    {
        if (!class_exists('\FluentCommunity\Modules\Course\Model\CourseLesson')) {
            return new \WP_Error('ledgr_no_courses', 'FluentCommunity course module is not available', ['status' => 501]);
        }

        $courseId = (int) $request['course_id'];
        $withBody = (bool) $request->get_param('with_body');

        $out = [];
        try {
            $lessons = \FluentCommunity\Modules\Course\Model\CourseLesson::whereHas('course', function ($q) use ($courseId) {
                $q->where('id', $courseId);
            })->orderBy('priority', 'asc')->get();

            foreach ($lessons as $lesson) {
                $row = [
                    'id'     => $lesson->id,
                    'title'  => $lesson->title,
                    'status' => $lesson->status,
                ];
                if ($withBody) {
                    $row['body_html'] = $lesson->message_rendered ?: wpautop((string) $lesson->message);
                }
                $out[] = $row;
            }
        } catch (\Exception $e) {
            return new \WP_Error('ledgr_lessons_failed', $e->getMessage(), ['status' => 500]);
        }

        return rest_ensure_response(['course_id' => $courseId, 'lessons' => $out]);
    }

    /* ---------------------------------------------------------------
     * Internals
     * ------------------------------------------------------------- */

    /**
     * Non-admins may only post into spaces they belong to. Site admins bypass.
     * Fails CLOSED for non-admins if FluentCommunity's membership schema can't
     * be read (better a 403 than a stranger posting into a secret space).
     */
    private function assertSpaceMembership($spaceId)
    {
        if (current_user_can('manage_options')) {
            return true;
        }
        try {
            $isMember = \FluentCommunity\App\Models\SpaceUserPivot::where('space_id', $spaceId)
                ->where('user_id', get_current_user_id())
                ->whereIn('status', ['active', 'moderator', 'admin'])
                ->exists();
            if ($isMember) {
                return true;
            }
            // Public spaces accept posts from any logged-in user, mirroring the portal.
            $space = \FluentCommunity\App\Models\Space::find($spaceId);
            if ($space && $space->privacy === 'public') {
                return true;
            }
        } catch (\Exception $e) {
            // fall through to the 403
        }
        return new \WP_Error('ledgr_not_member', 'You are not a member of this space', ['status' => 403]);
    }

    private function createSpacePostFromItem($spaceId, $userId, array $item)
    {
        $guid = isset($item['guid']) ? (string) $item['guid'] : (isset($item['url']) ? $item['url'] : '');
        if (!$guid) {
            return null;
        }

        $hash = md5($guid);
        $seen = get_option('ledgr_fb_rss_seen', []);
        if (in_array($hash, $seen)) {
            return null;
        }

        $title   = isset($item['title']) ? sanitize_text_field($item['title']) : '';
        $url     = isset($item['url']) ? esc_url_raw($item['url']) : '';
        $excerpt = isset($item['excerpt']) ? sanitize_textarea_field($item['excerpt']) : '';

        $message = trim($title . "\n\n" . $excerpt . "\n\n" . $url);
        $html    = '<p><strong>' . esc_html($title) . '</strong></p>'
            . ($excerpt ? '<p>' . esc_html($excerpt) . '</p>' : '')
            . ($url ? '<p><a href="' . esc_url($url) . '">' . esc_html($url) . '</a></p>' : '');

        try {
            $feed = \FluentCommunity\App\Models\Feed::create([
                'user_id'          => $userId ?: get_current_user_id(),
                'space_id'         => $spaceId,
                'title'            => $title,
                'message'          => $message,
                'message_rendered' => $html,
                'type'             => 'text',
                'status'           => 'published',
            ]);
        } catch (\Exception $e) {
            return null;
        }

        $seen[] = $hash;
        if (count($seen) > 3000) {
            $seen = array_slice($seen, -2000);
        }
        update_option('ledgr_fb_rss_seen', $seen, false);

        do_action('ledgr_fb/rss_post_created', $feed, $item);

        return $feed->id;
    }

    private function splitName($raw)
    {
        $raw   = trim(preg_replace('/[\w.+\-]+@[\w\-]+\.[\w.\-]+|#[a-z0-9\-_]+/i', '', (string) $raw));
        $raw   = trim(preg_replace('/[^\p{L}\p{M}\s\'\-.]/u', '', $raw));
        $parts = preg_split('/\s+/', $raw, 2, PREG_SPLIT_NO_EMPTY);

        return [
            isset($parts[0]) ? $parts[0] : '',
            isset($parts[1]) ? $parts[1] : '',
        ];
    }

    private function applyTags($contact, array $slugs)
    {
        $applied = [];

        if (!class_exists('\FluentCrm\App\Models\Tag')) {
            return $applied;
        }

        $tagIds = [];
        foreach (array_unique(array_map('strtolower', $slugs)) as $slug) {
            $slug = sanitize_title($slug);
            if (!$slug) {
                continue;
            }
            $tag = \FluentCrm\App\Models\Tag::firstOrCreate(
                ['slug' => $slug],
                ['title' => ucwords(str_replace('-', ' ', $slug))]
            );
            $tagIds[]  = $tag->id;
            $applied[] = $tag->title;
        }

        if ($tagIds && method_exists($contact, 'attachTags')) {
            $contact->attachTags($tagIds);
        }

        return $applied;
    }

    private function sideloadToMedia(array $file)
    {
        if (!function_exists('media_handle_sideload')) {
            require_once ABSPATH . 'wp-admin/includes/media.php';
            require_once ABSPATH . 'wp-admin/includes/file.php';
            require_once ABSPATH . 'wp-admin/includes/image.php';
        }

        $tmp = [
            'name'     => sanitize_file_name($file['name']),
            'type'     => $file['type'],
            'tmp_name' => $file['tmp_name'],
            'error'    => $file['error'],
            'size'     => $file['size'],
        ];

        $attachmentId = media_handle_sideload($tmp, 0, 'Ledgr handwritten note');

        if (is_wp_error($attachmentId)) {
            return null;
        }

        return wp_get_attachment_url($attachmentId);
    }

    private function validateUpload($file, array $mimes, $maxBytes)
    {
        if (!empty($file['error'])) {
            return new \WP_Error('ledgr_upload_error', 'Upload error code ' . $file['error'], ['status' => 400]);
        }
        if ($file['size'] > $maxBytes) {
            return new \WP_Error('ledgr_too_large', 'File exceeds size limit', ['status' => 413]);
        }
        $check = wp_check_filetype_and_ext($file['tmp_name'], $file['name']);
        if (!in_array($check['type'], $mimes) && !in_array($file['type'], $mimes)) {
            return new \WP_Error('ledgr_bad_type', 'Unsupported file type', ['status' => 415]);
        }
        return true;
    }

    /**
     * Mirrors TaskController@handleTaskCoverImageUpload:
     * FileSystem::put → TaskService::uploadMediaFileFromWpEditor(TASK_DESCRIPTION)
     * → settings.cover = { imageId, backgroundImage } → CommentService::createPublicUrl.
     */
    private function attachCover($task, $boardId, array $png)
    {
        $uploadInfo = \FluentBoards\App\Services\Libs\FileSystem::setSubDir('board_' . $boardId)
            ->put([new Ledgr_FB_File_Adapter($png)]);

        if (is_wp_error($uploadInfo) || empty($uploadInfo[0]) || !empty($uploadInfo[0]['error'])) {
            return null;
        }

        $fileData = $uploadInfo[0];

        $taskService = new \FluentBoards\App\Services\TaskService();
        $attachment  = $taskService->uploadMediaFileFromWpEditor(
            $task->id,
            $fileData,
            \FluentBoards\App\Services\Constant::TASK_DESCRIPTION
        );

        if (defined('FLUENT_BOARDS_PRO_VERSION') && class_exists('\FluentBoardsPro\App\Services\AttachmentService')) {
            try {
                $mediaData = (new \FluentBoardsPro\App\Services\AttachmentService())->processMediaData($fileData, $png);
                $attachment->driver    = $mediaData['driver'];
                $attachment->file_path = $mediaData['file_path'];
                $attachment->full_url  = $mediaData['full_url'];
                $attachment->save();
            } catch (\Exception $e) {
                // Local storage already succeeded; Pro driver is best-effort.
            }
        }

        $publicUrl = (new \FluentBoards\App\Services\CommentService())->createPublicUrl($attachment, $boardId);

        $settings = $task->settings;
        $settings['cover'] = [
            'imageId'         => $attachment->id,
            'backgroundImage' => $publicUrl,
        ];
        $task->settings = $settings;
        $task->save();

        return $publicUrl;
    }

    private function storeStrokes($task, $boardId, array $strokes)
    {
        $err = $this->validateUpload($strokes, ['application/gzip', 'application/x-gzip', 'application/json', 'application/octet-stream'], self::MAX_STROKE_BYTES);
        if (is_wp_error($err)) {
            return;
        }

        $dir = \FluentBoards\App\Services\Libs\FileSystem::setSubDir('board_' . $boardId)->getDir();

        if (!is_dir($dir)) {
            wp_mkdir_p($dir);
        }

        $filename = 'ledgr-strokes-' . $task->id . '-' . time() . '.json.gz';
        $dest     = $dir . DIRECTORY_SEPARATOR . $filename;

        if (@move_uploaded_file($strokes['tmp_name'], $dest) || @copy($strokes['tmp_name'], $dest)) {
            $this->setTaskMeta($task->id, self::META_STROKES, [
                'file'      => $filename,
                'bytes'     => filesize($dest),
                'stored_at' => current_time('mysql'),
            ]);
        }
    }

    /**
     * Exact-email-only auto-match against FluentCRM.
     * Name-only candidates are returned as suggestions, never associated.
     */
    private function matchCrmContact(array $entities)
    {
        if (!function_exists('FluentCrmApi')) {
            return null;
        }

        $emails = isset($entities['emails']) && is_array($entities['emails']) ? $entities['emails'] : [];

        foreach ($emails as $email) {
            $email = sanitize_email($email);
            if (!$email) {
                continue;
            }
            try {
                $contact = FluentCrmApi('contacts')->getContact($email);
                if ($contact) {
                    return [
                        'id'      => (int) $contact->id,
                        'email'   => $contact->email,
                        'name'    => trim($contact->first_name . ' ' . $contact->last_name),
                        'matched' => 'email',
                    ];
                }
            } catch (\Exception $e) {
                continue;
            }
        }

        return null;
    }

    private function setTaskMeta($taskId, $key, $value)
    {
        $meta = \FluentBoards\App\Models\TaskMeta::firstOrNew([
            'task_id' => $taskId,
            'key'     => $key,
        ]);
        $meta->value = $value;
        $meta->save();
    }

    private function getTaskMeta($taskId, $key)
    {
        $meta = \FluentBoards\App\Models\TaskMeta::where('task_id', $taskId)
            ->where('key', $key)
            ->first();

        return $meta ? $meta->value : null;
    }

    private function coverUrl($task)
    {
        $settings = $task->settings;
        return isset($settings['cover']['backgroundImage']) ? $settings['cover']['backgroundImage'] : null;
    }
}

add_action('plugins_loaded', ['Ledgr_FB_Bridge', 'boot'], 20);
