/**
 * Host half of dsh-hive-vessel.
 *
 * Everything the vessel does happens in the browser (lib/client.js): the page
 * subscribes to the hive bridge directly, so the host only records where that
 * bridge is. Keeping the host half is what lets `dsh plugin add` install the
 * package as a bundle and gives users one config row to edit.
 *
 * SPDX-License-Identifier: MIT
 */

export const name = 'hive-vessel'

/** Default bridge the hive.deepseek addon listens on. */
export const DEFAULT_URL = 'http://127.0.0.1:7925'

/**
 * @param {import('@deepseek-ai/cordis').Context} ctx
 * @param {{ url?: string, token?: string }} [config]
 */
export function apply(ctx, config = {}) {
  const url = config.url ?? DEFAULT_URL
  ctx.logger?.('hive-vessel')?.info?.(`hive bridge: ${url}`)
}
