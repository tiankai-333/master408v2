import { post, postWithLoadTip } from '@/utils/request'

export default {
  login: query => postWithLoadTip(`/api/admin/login`, query),
  logout: query => post(`/api/admin/logout`, query)
}
