cmake_minimum_required(VERSION 3.18)
project("ncnn-sr")

# 暂时所有 ABI 都用 stub(超分功能已放弃,ncnn 预编译库已移除)
# 后台播放功能不需要 ncnn,这个 stub 只是满足 System.loadLibrary("ncnn-sr") 的符号要求
add_library(ncnn-sr SHARED ncnn_sr_stub.cpp)

find_library(log-lib log)
target_link_libraries(ncnn-sr ${log-lib})
