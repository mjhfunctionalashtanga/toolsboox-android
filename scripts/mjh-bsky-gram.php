<?php
/**
 * Plugin Name: MJH Bluesky Gram Queue
 * Description: Share ONE gram from Ledger to Bluesky. Ledger POSTs the card's PNG + a short
 *   text here; the VPS (bsky_gram.py) drains the queue and does the AT Protocol work, because
 *   the Bluesky app password lives on the VPS and must never ship in an app binary. Same
 *   X-MF-Secret auth and the same queue/ack shape as bsky-reply — a third auth scheme would
 *   just be a third thing to get wrong. ("I think that the gram picks individually can be
 *   shared on Bluesky" — the 08-15 field page; feed-sourced grams only, enforced app-side:
 *   email grams are private correspondence and book grams are copyrighted passages.)
 *   Needs MJH_SYND_SECRET defined by the host (wp-config.php); without it every route answers 503.
 * Version: 1.0.0
 */
if ( ! defined( 'ABSPATH' ) ) { exit; }

/**
 * State lives on a non-public CPT, one post per staged gram:
 *   _bskyg_state    'pending' → the VPS has not posted it yet; 'posted' → live
 *   _bskyg_image    uploads URL of the card PNG (sideloaded here, fetched by the VPS)
 *   _bskyg_alt      the image's alt text
 *   _bskyg_link     the gram's source URL — becomes a link facet in the post text
 *   _bskyg_gram_id  the element's own id — the idempotency key; a re-tap on a slow
 *                   connection must not become two Bluesky posts
 *   _bsky_uri/_bsky_url  filled in by the ack, so the share is addressable afterwards
 */
const MJH_BSKYG_CPT   = 'mjh_bsky_gram';
const MJH_BSKYG_STATE = '_bskyg_state';
const MJH_BSKYG_IMAGE = '_bskyg_image';
const MJH_BSKYG_ALT   = '_bskyg_alt';
const MJH_BSKYG_LINK  = '_bskyg_link';
const MJH_BSKYG_GID   = '_bskyg_gram_id';
const MJH_BSKYG_MAX_GRAPHEMES = 300;

/**
 * The shared X-MF-Secret is the HOST's to define — wp-config.php, or any plugin that loads before
 * a request arrives — and never a literal in this file, which is published with the app's source.
 * Not defined (or empty) → every route answers 503 saying so: closed, never open. An empty secret
 * must never reach the comparison, because hash_equals( '', '' ) is true.
 */
function mjh_bskyg_secret() {
	return ( defined( 'MJH_SYND_SECRET' ) && is_string( MJH_SYND_SECRET ) ) ? MJH_SYND_SECRET : '';
}

/** null when the request carries the site's secret; otherwise the WP_Error to answer with. */
function mjh_bskyg_denied( WP_REST_Request $r ) {
	$secret = mjh_bskyg_secret();
	if ( '' === $secret ) {
		error_log( 'mjh-bsky-gram: MJH_SYND_SECRET is not defined on this site; every route refuses (503)' );
		return new WP_Error(
			'not_configured',
			'The Bluesky gram queue is not set up on this site: define MJH_SYND_SECRET in wp-config.php',
			[ 'status' => 503 ]
		);
	}
	if ( ! hash_equals( $secret, (string) $r->get_header( 'x-mf-secret' ) ) ) {
		return new WP_Error( 'forbidden', 'bad secret', [ 'status' => 403 ] );
	}
	return null;
}

function mjh_bskyg_graphemes( $s ) {
	if ( function_exists( 'grapheme_strlen' ) ) {
		$n = grapheme_strlen( $s );
		if ( is_int( $n ) ) { return $n; }
	}
	return (int) mb_strlen( (string) $s, 'UTF-8' );
}

add_action( 'init', function () {
	register_post_type( MJH_BSKYG_CPT, [
		'public'              => false,
		'show_ui'             => false,
		'exclude_from_search' => true,
		'rewrite'             => false,
		'supports'            => [ 'title' ],
	] );
} );

add_action( 'rest_api_init', function () {
	$routes = [
		'bsky-gram'       => [ 'POST', 'mjh_bskyg_stage' ],
		'bsky-gram-queue' => [ 'GET',  'mjh_bskyg_queue' ],
		'bsky-gram-ack'   => [ 'POST', 'mjh_bskyg_ack' ],
	];
	foreach ( $routes as $route => $h ) {
		register_rest_route( 'mjh/v1', "/{$route}", [
			'methods'             => $h[0],
			'permission_callback' => '__return_true',   // the X-MF-Secret header IS the permission
			'callback'            => $h[1],
		] );
	}
} );

/**
 * POST /bsky-gram — Ledger stages a share.
 * Body (JSON): { text, image_b64, alt?, link?, gram_id? }
 * The PNG is sideloaded into uploads NOW so the queue row is a URL, not a megabyte of base64
 * in postmeta; the VPS fetches it exactly as it fetches a handwriting reply's PNG.
 */
function mjh_bskyg_stage( WP_REST_Request $r ) {
	$denied = mjh_bskyg_denied( $r );
	if ( $denied ) { return $denied; }

	$text = trim( (string) $r->get_param( 'text' ) );
	$b64  = (string) $r->get_param( 'image_b64' );
	if ( '' === $text && '' === $b64 ) {
		return new WP_Error( 'empty', 'a share needs words or a picture', [ 'status' => 400 ] );
	}
	if ( mjh_bskyg_graphemes( $text ) > MJH_BSKYG_MAX_GRAPHEMES ) {
		return new WP_Error( 'too_long', 'over the ' . MJH_BSKYG_MAX_GRAPHEMES . '-grapheme ceiling', [ 'status' => 400 ] );
	}

	// Idempotency: the element's id. A re-tap (double-tap, retry after a timeout whose POST
	// actually landed) answers ok+duplicate instead of minting a second post.
	$gram_id = sanitize_text_field( (string) $r->get_param( 'gram_id' ) );
	if ( '' !== $gram_id ) {
		$dupe = get_posts( [
			'post_type'   => MJH_BSKYG_CPT,
			'post_status' => 'any',
			'numberposts' => 1,
			'fields'      => 'ids',
			'meta_key'    => MJH_BSKYG_GID,
			'meta_value'  => $gram_id,
		] );
		if ( $dupe ) {
			return [ 'ok' => true, 'duplicate' => true, 'id' => (int) $dupe[0] ];
		}
	}

	$image_url = '';
	if ( '' !== $b64 ) {
		$bytes = base64_decode( $b64, true );
		if ( false === $bytes || strlen( $bytes ) < 100 ) {
			return new WP_Error( 'bad_image', 'image_b64 did not decode', [ 'status' => 400 ] );
		}
		if ( strlen( $bytes ) > 8 * 1024 * 1024 ) {
			return new WP_Error( 'image_too_big', 'card PNG over 8 MB', [ 'status' => 400 ] );
		}
		$up = wp_upload_bits( 'bsky-gram-' . gmdate( 'Ymd-His' ) . '-' . wp_generate_password( 6, false ) . '.png', null, $bytes );
		if ( ! empty( $up['error'] ) ) {
			return new WP_Error( 'upload_failed', (string) $up['error'], [ 'status' => 500 ] );
		}
		$image_url = (string) $up['url'];
	}

	$id = wp_insert_post( [
		'post_type'   => MJH_BSKYG_CPT,
		'post_status' => 'private',
		'post_title'  => mb_substr( $text ?: 'gram', 0, 80 ),
		'post_content'=> $text,
	], true );
	if ( is_wp_error( $id ) ) { return $id; }

	update_post_meta( $id, MJH_BSKYG_STATE, 'pending' );
	if ( $image_url ) { update_post_meta( $id, MJH_BSKYG_IMAGE, $image_url ); }
	update_post_meta( $id, MJH_BSKYG_ALT, sanitize_text_field( (string) $r->get_param( 'alt' ) ) );
	update_post_meta( $id, MJH_BSKYG_LINK, esc_url_raw( (string) $r->get_param( 'link' ) ) );
	if ( '' !== $gram_id ) { update_post_meta( $id, MJH_BSKYG_GID, $gram_id ); }

	return [ 'ok' => true, 'duplicate' => false, 'id' => (int) $id ];
}

/** GET /bsky-gram-queue?limit=N — pending shares, oldest first (a drip queue, like syndicate). */
function mjh_bskyg_queue( WP_REST_Request $r ) {
	$denied = mjh_bskyg_denied( $r );
	if ( $denied ) { return $denied; }
	header( 'X-LiteSpeed-Cache-Control: no-cache' ); nocache_headers();

	$limit = (int) $r->get_param( 'limit' );
	$limit = $limit > 0 ? min( 20, $limit ) : 10;

	$posts = get_posts( [
		'post_type'   => MJH_BSKYG_CPT,
		'post_status' => 'private',
		'numberposts' => $limit,
		'orderby'     => 'date',
		'order'       => 'ASC',
		'meta_key'    => MJH_BSKYG_STATE,
		'meta_value'  => 'pending',
	] );

	$items = [];
	foreach ( $posts as $p ) {
		$items[] = [
			'id'        => (int) $p->ID,
			'text'      => (string) $p->post_content,
			'image_url' => (string) get_post_meta( $p->ID, MJH_BSKYG_IMAGE, true ),
			'image_alt' => (string) get_post_meta( $p->ID, MJH_BSKYG_ALT, true ),
			'link'      => (string) get_post_meta( $p->ID, MJH_BSKYG_LINK, true ),
		];
	}
	return [ 'items' => $items ];
}

/** POST /bsky-gram-ack — { id, uri, url } → the share is live; remember its address. */
function mjh_bskyg_ack( WP_REST_Request $r ) {
	$denied = mjh_bskyg_denied( $r );
	if ( $denied ) { return $denied; }
	$id = (int) $r->get_param( 'id' );
	$p  = get_post( $id );
	if ( ! $p || MJH_BSKYG_CPT !== $p->post_type ) {
		return new WP_Error( 'not_found', 'no such staged gram', [ 'status' => 404 ] );
	}
	update_post_meta( $id, MJH_BSKYG_STATE, 'posted' );
	update_post_meta( $id, '_bsky_uri', sanitize_text_field( (string) $r->get_param( 'uri' ) ) );
	update_post_meta( $id, '_bsky_url', esc_url_raw( (string) $r->get_param( 'url' ) ) );
	return [ 'ok' => true ];
}
